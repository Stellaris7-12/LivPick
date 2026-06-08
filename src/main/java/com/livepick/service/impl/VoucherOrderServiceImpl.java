package com.livepick.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.livepick.dto.Result;
import com.livepick.entity.SeckillVoucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.enums.ReconcileLogType;
import com.livepick.enums.ReconciliationStatus;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.mq.delay.OrderTimeoutDelayQueueManager;
import com.livepick.mq.message.OrderTimeoutMessage;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.IVoucherOrderService;
import com.livepick.service.MqOutboxService;
import com.livepick.service.RedisReservationRecoveryService;
import com.livepick.service.RedisTraceService;
import com.livepick.service.SeckillMessageAssembler;
import com.livepick.service.SeckillReservationService;
import com.livepick.service.VoucherReconcileLogService;
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
import org.springframework.context.ApplicationContext;
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
    private final OrderTimeoutDelayQueueManager orderTimeoutDelayQueueManager;
    private final SeckillReservationService seckillReservationService;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final BenchmarkRuntimeConfigService runtimeConfigService;
    private final SeckillMessageAssembler seckillMessageAssembler;
    private final MqOutboxService mqOutboxService;
    private final VoucherReconcileLogService voucherReconcileLogService;
    private final RedisReservationRecoveryService redisReservationRecoveryService;
    private final RedisTraceService redisTraceService;
    private final ApplicationContext applicationContext;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("优惠券不存在");
        }

        Long reusableOrderId = seckillReservationService.getReusableOrderId(voucherId, userId);
        long orderId = reusableOrderId != null ? reusableOrderId : redisIdWorker.nextId("order");
        long traceId = redisIdWorker.nextId("trace");
        LocalDateTime createTime = LocalDateTime.now();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId),
                String.valueOf(traceId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(redisTraceService.resolveTraceTtlSeconds(seckillVoucher.getEndTime()))
        );
        long executeResult = result == null ? -1L : result;
        if (executeResult > 0) {
            if (executeResult == 1L) {
                benchmarkMetricsService.incrementLuaStockRejected();
            } else if (executeResult == 2L) {
                benchmarkMetricsService.incrementLuaDuplicateRejected();
            }
            return Result.fail(executeResult == 1L ? "库存不足" : "不能重复下单");
        }

        int afterQty = Math.abs((int) executeResult) - 1;
        int beforeQty = afterQty + 1;
        benchmarkMetricsService.incrementApiAccepted();
        if (runtimeConfigService.isConsumerPaused()) {
            benchmarkMetricsService.incrementConsumerPauseAccepted();
        }

        SeckillMessageAssembler.SeckillAcceptContext context = seckillMessageAssembler.buildAcceptContext(
                traceId,
                orderId,
                userId,
                voucherId,
                beforeQty,
                -1,
                afterQty,
                seckillVoucher,
                createTime
        );

        try {
            applicationContext.getBean(VoucherOrderServiceImpl.class).persistAcceptance(context);
            benchmarkMetricsService.incrementOutboxPending();
            return Result.ok(orderId);
        } catch (Exception e) {
            redisReservationRecoveryService.rollbackBeforeOrderCreated(voucherId, userId, orderId, traceId, "SECKILL_API_ACCEPT");
            log.error("persist seckill acceptance failed, orderId={}, traceId={}", orderId, traceId, e);
            return Result.fail("下单繁忙，请稍后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();
        RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        boolean locked = redisLock.tryLock();
        if (!locked) {
            log.warn("retry consume because lock acquisition failed, orderId={}, userId={}, voucherId={}",
                    message.getOrderId(), userId, voucherId);
            throw new IllegalStateException("seckill consume lock busy");
        }

        try {
            VoucherOrder existingOrder = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .one();
            if (existingOrder != null) {
                boolean consistent = handleExistingOrder(message, existingOrder);
                if (consistent) {
                    mqOutboxService.markAckedByMessageId(message.getMessageId());
                    voucherReconcileLogService.markOrderStatus(message.getOrderId(), ReconciliationStatus.CONSISTENT);
                }
                return;
            }

            if (!deductDbStock(voucherId)) {
                handleSeckillOrderFailure(message, "DB_STOCK_REJECTED", "db stock insufficient after kafka consume");
                return;
            }

            LocalDateTime createTime = resolveCreateTime(message);
            VoucherOrder voucherOrder = new VoucherOrder()
                    .setId(message.getOrderId())
                    .setUserId(userId)
                    .setVoucherId(voucherId)
                    .setPayType(1)
                    .setStatus(OrderStatusConstants.UNPAID)
                    .setCreateTime(createTime)
                    .setUpdateTime(LocalDateTime.now())
                    .setReconciliationStatus(ReconciliationStatus.PENDING.name());
            try {
                boolean saved = save(voucherOrder);
                if (!saved) {
                    throw new IllegalStateException("save voucher order failed");
                }
                benchmarkMetricsService.incrementConsumerCreated();
            } catch (DuplicateKeyException duplicateKeyException) {
                benchmarkMetricsService.incrementConsumerDuplicate();
                log.info("ignore duplicate key while creating order, orderId={}, userId={}, voucherId={}",
                        message.getOrderId(), userId, voucherId);
                return;
            }

            mqOutboxService.markAckedByMessageId(message.getMessageId());
            appendReconcileLog(message, ReconcileLogType.CREATE, ReconciliationStatus.CONSISTENT,
                    "KAFKA_CONSUMER", "order created");
            update()
                    .eq("id", message.getOrderId())
                    .set("reconciliation_status", ReconciliationStatus.CONSISTENT.name())
                    .set("update_time", LocalDateTime.now())
                    .update();
            voucherReconcileLogService.markOrderStatus(message.getOrderId(), ReconciliationStatus.CONSISTENT);
            seckillReservationService.clearReusableOrderId(voucherId, userId, message.getOrderId());
            offerTimeoutMessage(message.getOrderId(), userId, voucherId, createTime);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrderFailure(SeckillOrderMessage message, String source, String detail) {
        appendReconcileLog(message, ReconcileLogType.FAIL, ReconciliationStatus.ABNORMAL, source, detail);
        voucherReconcileLogService.markOrderStatus(message.getOrderId(), ReconciliationStatus.ABNORMAL);
        mqOutboxService.markAckedByMessageId(message.getMessageId());
        redisReservationRecoveryService.rollbackBeforeOrderCreated(
                message.getVoucherId(),
                message.getUserId(),
                message.getOrderId(),
                message.getTraceId(),
                source
        );
        redisTraceService.deleteTrace(message.getVoucherId(), message.getTraceId());
        benchmarkMetricsService.incrementReconcileAbnormal();
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

    @Transactional(rollbackFor = Exception.class)
    protected void persistAcceptance(SeckillMessageAssembler.SeckillAcceptContext context) {
        mqOutboxService.save(context.getOutboxMessage());
        voucherReconcileLogService.save(context.getReconcileLog());
    }

    private boolean handleExistingOrder(SeckillOrderMessage message, VoucherOrder existingOrder) {
        Long voucherId = message.getVoucherId();
        Long userId = message.getUserId();

        if (OrderStatusConstants.CANCELLED == existingOrder.getStatus()
                && existingOrder.getId().equals(message.getOrderId())) {
            if (!deductDbStock(voucherId)) {
                handleSeckillOrderFailure(message, "REACTIVATE_STOCK_REJECTED",
                        "db stock insufficient while reactivating cancelled order");
                return false;
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
                    .set("reconciliation_status", ReconciliationStatus.CONSISTENT.name())
                    .set("update_time", LocalDateTime.now())
                    .update();
            if (!reactivated) {
                handleSeckillOrderFailure(message, "REACTIVATE_RACE_LOST",
                        "reactivate cancelled order lost optimistic race");
                return false;
            }

            seckillReservationService.clearReusableOrderId(voucherId, userId, message.getOrderId());
            benchmarkMetricsService.incrementConsumerReactivated();
            appendReconcileLog(message, ReconcileLogType.CREATE, ReconciliationStatus.CONSISTENT,
                    "KAFKA_CONSUMER_REACTIVATE", "reactivated cancelled order");
            offerTimeoutMessage(existingOrder.getId(), userId, voucherId, createTime);
            return true;
        }

        benchmarkMetricsService.incrementConsumerDuplicate();
        log.info("skip duplicated or stale consume, orderId={}, existingOrderId={}, existingStatus={}, userId={}, voucherId={}",
                message.getOrderId(), existingOrder.getId(), existingOrder.getStatus(), userId, voucherId);
        return true;
    }

    private void appendReconcileLog(SeckillOrderMessage message, ReconcileLogType logType,
                                    ReconciliationStatus status, String source, String detail) {
        voucherReconcileLogService.save(new com.livepick.entity.VoucherReconcileLog()
                .setId(redisIdWorker.nextId("reconcile"))
                .setMessageId(message.getMessageId())
                .setTraceId(message.getTraceId())
                .setOrderId(message.getOrderId())
                .setVoucherId(message.getVoucherId())
                .setUserId(message.getUserId())
                .setLogType(logType.name())
                .setSource(source)
                .setDetail(detail)
                .setBeforeQty(message.getBeforeQty())
                .setChangeQty(message.getChangeQty())
                .setAfterQty(message.getAfterQty())
                .setReconciliationStatus(status.name())
                .setCreatedTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));
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
