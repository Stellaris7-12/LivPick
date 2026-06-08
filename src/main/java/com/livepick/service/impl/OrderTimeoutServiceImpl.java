package com.livepick.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.livepick.config.LivPickProperties;
import com.livepick.entity.VoucherOrder;
import com.livepick.enums.ReconcileLogType;
import com.livepick.enums.ReconciliationStatus;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.IOrderTimeoutService;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.RedisReservationRecoveryService;
import com.livepick.service.SeckillReservationService;
import com.livepick.service.VoucherReconcileLogService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import com.livepick.utils.RedisIdWorker;
import com.livepick.utils.OrderStatusConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutServiceImpl implements IOrderTimeoutService {

    private final VoucherOrderMapper voucherOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;
    private final LivPickProperties livPickProperties;
    private final SeckillReservationService seckillReservationService;
    private final RedisReservationRecoveryService redisReservationRecoveryService;
    private final VoucherReconcileLogService voucherReconcileLogService;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final BenchmarkRuntimeConfigService runtimeConfigService;
    private final RedisIdWorker redisIdWorker;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeTimeoutOrder(Long orderId) {
        return closeTimeoutOrder(orderId, "UNKNOWN");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeTimeoutOrder(Long orderId, String triggerSource) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }

        boolean closed = voucherOrderMapper.update(
                null,
                new UpdateWrapper<VoucherOrder>()
                        .eq("id", orderId)
                        .eq("status", OrderStatusConstants.UNPAID)
                        .set("status", OrderStatusConstants.CANCELLED)
                        .set("update_time", LocalDateTime.now())
        ) > 0;
        if (!closed) {
            return false;
        }

        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        redisReservationRecoveryService.rollbackAfterTimeout(
                order.getVoucherId(),
                order.getUserId(),
                order.getId(),
                null,
                triggerSource
        );
        voucherOrderMapper.update(
                null,
                new UpdateWrapper<VoucherOrder>()
                        .eq("id", orderId)
                        .set("reconciliation_status", ReconciliationStatus.CONSISTENT.name())
                        .set("update_time", LocalDateTime.now())
        );
        voucherReconcileLogService.save(new com.livepick.entity.VoucherReconcileLog()
                .setId(redisIdWorker.nextId("reconcile"))
                .setOrderId(order.getId())
                .setVoucherId(order.getVoucherId())
                .setUserId(order.getUserId())
                .setLogType(ReconcileLogType.TIMEOUT.name())
                .setSource(triggerSource)
                .setDetail("timeout order closed and reservation restored")
                .setReconciliationStatus(ReconciliationStatus.CONSISTENT.name())
                .setCreatedTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));

        benchmarkMetricsService.incrementTimeoutClosed();
        benchmarkMetricsService.incrementTimeoutExpired();
        if ("DELAY_QUEUE".equalsIgnoreCase(triggerSource)) {
            benchmarkMetricsService.incrementTimeoutDelayQueueTriggered();
        } else if ("FALLBACK".equalsIgnoreCase(triggerSource)) {
            benchmarkMetricsService.incrementTimeoutFallbackTriggered();
        }
        Duration timeoutDuration = runtimeConfigService.getOrderTimeoutDuration();
        LocalDateTime expireAt = order.getCreateTime().plus(timeoutDuration);
        benchmarkMetricsService.recordTimeoutLag(Duration.between(expireAt, LocalDateTime.now()).toMillis());
        return true;
    }

    @Override
    public void scanAndCloseTimeoutOrders() {
        Duration timeoutDuration = runtimeConfigService.getOrderTimeoutDuration();
        LocalDateTime expireBefore = LocalDateTime.now().minus(timeoutDuration);
        while (true) {
            List<VoucherOrder> timeoutOrders = voucherOrderMapper.selectList(
                    new QueryWrapper<VoucherOrder>()
                            .eq("status", OrderStatusConstants.UNPAID)
                            .lt("create_time", expireBefore)
                            .last("LIMIT " + livPickProperties.getOrder().getTimeoutScanBatchSize())
            );
            if (timeoutOrders.isEmpty()) {
                return;
            }
            timeoutOrders.forEach(order -> {
                try {
                    closeTimeoutOrder(order.getId(), "FALLBACK");
                } catch (Exception e) {
                    log.error("close timeout order failed, orderId={}", order.getId(), e);
                }
            });
            if (timeoutOrders.size() < livPickProperties.getOrder().getTimeoutScanBatchSize()) {
                return;
            }
        }
    }
}
