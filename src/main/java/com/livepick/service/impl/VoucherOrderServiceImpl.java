package com.livepick.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.livepick.config.LivPickProperties;
import com.livepick.dto.Result;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.mq.delay.OrderTimeoutDelayQueueManager;
import com.livepick.mq.message.OrderTimeoutMessage;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.IVoucherOrderService;
import com.livepick.service.SeckillPendingSendService;
import com.livepick.service.SeckillReservationService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import com.livepick.utils.OrderStatusConstants;
import com.livepick.utils.RedisIdWorker;
import com.livepick.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;

import static com.livepick.utils.RedisConstants.LOCK_ORDER_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final LivPickKafkaProducer livPickKafkaProducer;
    private final OrderTimeoutDelayQueueManager orderTimeoutDelayQueueManager;
    private final LivPickProperties livPickProperties;
    private final SeckillReservationService seckillReservationService;
    private final SeckillPendingSendService seckillPendingSendService;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final BenchmarkRuntimeConfigService runtimeConfigService;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long reusableOrderId = seckillReservationService.getReusableOrderId(voucherId, userId);
        long orderId = reusableOrderId != null ? reusableOrderId : redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int executeResult = result == null ? -1 : result.intValue();
        if (executeResult != 0) {
            if (executeResult == 1) {
                benchmarkMetricsService.incrementLuaStockRejected();
            } else if (executeResult == 2) {
                benchmarkMetricsService.incrementLuaDuplicateRejected();
            }
            return Result.fail(executeResult == 1 ? "库存不足" : "不能重复下单");
        }
        benchmarkMetricsService.incrementApiAccepted();
        if (runtimeConfigService.isConsumerPaused()) {
            benchmarkMetricsService.incrementConsumerPauseAccepted();
        }

        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        message.setCreateTime(LocalDateTime.now());

        try {
            seckillPendingSendService.register(message);
        } catch (Exception e) {
            seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
            log.error("register pending seckill message failed, orderId={}", orderId, e);
            return Result.fail("下单繁忙，请稍后重试");
        }

        try {
            livPickKafkaProducer.sendSeckillOrder(message);
            seckillPendingSendService.clear(orderId);
        } catch (Exception e) {
            log.warn("send seckill order kafka message failed, will retry asynchronously, orderId={}", orderId, e);
        }
        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();
        RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            benchmarkMetricsService.incrementConsumerDuplicate();
            log.warn("skip duplicated consume request, orderId={}, userId={}, voucherId={}, failureStage=acquireLock",
                    message.getOrderId(), userId, voucherId);
            return;
        }

        try {
            VoucherOrder existingOrder = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .one();
            if (existingOrder != null) {
                handleExistingOrder(message, existingOrder);
                return;
            }

            if (!deductDbStock(voucherId)) {
                seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                log.warn("db stock insufficient after kafka consume, orderId={}, userId={}, voucherId={}, failureStage=deductDbStock",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            LocalDateTime createTime = resolveCreateTime(message);
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(message.getOrderId());
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setPayType(1);
            voucherOrder.setStatus(OrderStatusConstants.UNPAID);
            voucherOrder.setCreateTime(createTime);
            voucherOrder.setUpdateTime(LocalDateTime.now());
            try {
                boolean saved = save(voucherOrder);
                if (!saved) {
                    seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                    throw new IllegalStateException("save voucher order failed");
                }
                benchmarkMetricsService.incrementConsumerCreated();
            } catch (DuplicateKeyException duplicateKeyException) {
                seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                benchmarkMetricsService.incrementConsumerDuplicate();
                log.info("ignore duplicate key while creating order, orderId={}, userId={}, voucherId={}, failureStage=saveOrder",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            seckillReservationService.clearReusableOrderId(voucherId, userId, message.getOrderId());
            offerTimeoutMessage(message.getOrderId(), userId, voucherId, createTime);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean payOrder(Long orderId) {
        boolean updated = update()
                .eq("id", orderId)
                .eq("status", OrderStatusConstants.UNPAID)
                .set("status", OrderStatusConstants.PAID)
                .set("pay_time", LocalDateTime.now())
                .set("update_time", LocalDateTime.now())
                .update();
        if (updated) {
            benchmarkMetricsService.incrementPaySuccess();
        }
        return updated;
    }

    private void handleExistingOrder(SeckillOrderMessage message, VoucherOrder existingOrder) {
        Long voucherId = message.getVoucherId();
        Long userId = message.getUserId();

        if (OrderStatusConstants.CANCELLED == existingOrder.getStatus()
                && existingOrder.getId().equals(message.getOrderId())) {
            if (!deductDbStock(voucherId)) {
                seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                log.warn("db stock insufficient while reactivating cancelled order, orderId={}, userId={}, voucherId={}",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            LocalDateTime createTime = resolveCreateTime(message);
            boolean reactivated = update()
                    .eq("id", existingOrder.getId())
                    .eq("status", OrderStatusConstants.CANCELLED)
                    .set("status", OrderStatusConstants.UNPAID)
                    .set("pay_type", 1)
                    .set("create_time", createTime)
                    .set("pay_time", null)
                    .set("use_time", null)
                    .set("refund_time", null)
                    .set("update_time", LocalDateTime.now())
                    .update();
            if (!reactivated) {
                seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                log.warn("reactivate cancelled order lost optimistic race, orderId={}, userId={}, voucherId={}",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            seckillReservationService.clearReusableOrderId(voucherId, userId, message.getOrderId());
            benchmarkMetricsService.incrementConsumerReactivated();
            offerTimeoutMessage(existingOrder.getId(), userId, voucherId, createTime);
            return;
        }

        benchmarkMetricsService.incrementConsumerDuplicate();
        log.info("skip duplicated or stale consume, orderId={}, existingOrderId={}, existingStatus={}, userId={}, voucherId={}",
                message.getOrderId(), existingOrder.getId(), existingOrder.getStatus(), userId, voucherId);
    }

    private boolean deductDbStock(Long voucherId) {
        return seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
    }

    private LocalDateTime resolveCreateTime(SeckillOrderMessage message) {
        return message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime();
    }

    private void offerTimeoutMessage(Long orderId, Long userId, Long voucherId, LocalDateTime createTime) {
        OrderTimeoutMessage timeoutMessage = new OrderTimeoutMessage();
        timeoutMessage.setOrderId(orderId);
        timeoutMessage.setUserId(userId);
        timeoutMessage.setVoucherId(voucherId);
        timeoutMessage.setExpireAt(createTime.plus(runtimeConfigService.getOrderTimeoutDuration()));
        try {
            orderTimeoutDelayQueueManager.offer(timeoutMessage);
        } catch (Exception e) {
            log.error("offer timeout order message failed, fallback scan will handle it, orderId={}", orderId, e);
        }
    }
}
