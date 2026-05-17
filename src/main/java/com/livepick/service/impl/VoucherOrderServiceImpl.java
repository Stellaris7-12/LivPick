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
import org.springframework.transaction.interceptor.TransactionAspectSupport;

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

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int executeResult = result == null ? -1 : result.intValue();
        if (executeResult != 0) {
            return Result.fail(executeResult == 1 ? "库存不足" : "不能重复下单");
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
            log.warn("skip duplicated consume request, orderId={}, userId={}, voucherId={}, failureStage=acquireLock",
                    message.getOrderId(), userId, voucherId);
            return;
        }

        try {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.info("skip duplicated order consume, orderId={}, userId={}, voucherId={}, failureStage=duplicateQuery",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            boolean stockUpdated = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!stockUpdated) {
                seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                log.warn("db stock insufficient after kafka consume, orderId={}, userId={}, voucherId={}, failureStage=deductDbStock",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(message.getOrderId());
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setPayType(1);
            voucherOrder.setStatus(OrderStatusConstants.UNPAID);
            voucherOrder.setCreateTime(message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime());
            voucherOrder.setUpdateTime(LocalDateTime.now());
            try {
                boolean saved = save(voucherOrder);
                if (!saved) {
                    seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                    throw new IllegalStateException("save voucher order failed");
                }
            } catch (DuplicateKeyException duplicateKeyException) {
                log.info("ignore duplicate key while creating order, orderId={}, userId={}, voucherId={}, failureStage=saveOrder",
                        message.getOrderId(), userId, voucherId);
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return;
            }

            OrderTimeoutMessage timeoutMessage = new OrderTimeoutMessage();
            timeoutMessage.setOrderId(voucherOrder.getId());
            timeoutMessage.setUserId(voucherOrder.getUserId());
            timeoutMessage.setVoucherId(voucherOrder.getVoucherId());
            timeoutMessage.setExpireAt(voucherOrder.getCreateTime().plusMinutes(livPickProperties.getOrder().getTimeoutMinutes()));
            try {
                orderTimeoutDelayQueueManager.offer(timeoutMessage);
            } catch (Exception e) {
                log.error("offer timeout order message failed, fallback scan will handle it, orderId={}", voucherOrder.getId(), e);
            }
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean payOrder(Long orderId) {
        return update()
                .eq("id", orderId)
                .eq("status", OrderStatusConstants.UNPAID)
                .set("status", OrderStatusConstants.PAID)
                .set("pay_time", LocalDateTime.now())
                .set("update_time", LocalDateTime.now())
                .update();
    }
}
