package com.livepick.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.livepick.config.LivPickProperties;
import com.livepick.entity.SeckillVoucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.enums.ReconcileLogType;
import com.livepick.enums.ReconciliationStatus;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.MqOutboxService;
import com.livepick.service.RedisReservationRecoveryService;
import com.livepick.service.RedisTraceService;
import com.livepick.service.VoucherReconcileLogService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.utils.RedisConstants;
import com.livepick.utils.RedisIdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReconciliationTask {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTraceService redisTraceService;
    private final VoucherOrderMapper voucherOrderMapper;
    private final VoucherReconcileLogService voucherReconcileLogService;
    private final MqOutboxService mqOutboxService;
    private final RedisReservationRecoveryService redisReservationRecoveryService;
    private final ISeckillVoucherService seckillVoucherService;
    private final LivPickProperties livPickProperties;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final RedisIdWorker redisIdWorker;

    @Scheduled(fixedDelayString = "${livpick.reconcile.scan-interval-ms}")
    public void reconcile() {
        reconcileRedisTraceOrphans();
        rebuildMissingRedisState();
    }

    private void reconcileRedisTraceOrphans() {
        Set<String> traceKeys = stringRedisTemplate.keys(RedisConstants.SECKILL_TRACE_KEY + "*");
        if (traceKeys == null || traceKeys.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String traceKey : traceKeys) {
            Long voucherId = Long.valueOf(traceKey.substring(RedisConstants.SECKILL_TRACE_KEY.length()));
            Map<String, String> traces = redisTraceService.readAll(voucherId);
            for (Map.Entry<String, String> entry : traces.entrySet()) {
                RedisTraceService.TraceSnapshot snapshot = redisTraceService.parse(entry.getValue());
                if (snapshot.getTs() == null || now - snapshot.getTs() < livPickProperties.getReconcile().getGracePeriodMs()) {
                    continue;
                }
                VoucherOrder order = voucherOrderMapper.selectById(snapshot.getOrderId());
                boolean hasOutbox = voucherReconcileLogService.existsByTraceId(snapshot.getTraceId())
                        || mqOutboxService.findByMessageId(findMessageIdByTrace(snapshot.getTraceId())) != null;
                if (order == null && !hasOutbox) {
                    redisReservationRecoveryService.rollbackBeforeOrderCreated(
                            snapshot.getVoucherId(),
                            snapshot.getUserId(),
                            snapshot.getOrderId(),
                            snapshot.getTraceId(),
                            "RECONCILE_ORPHAN_TRACE"
                    );
                    voucherReconcileLogService.save(new com.livepick.entity.VoucherReconcileLog()
                            .setId(redisIdWorker.nextId("reconcile"))
                            .setTraceId(snapshot.getTraceId())
                            .setOrderId(snapshot.getOrderId())
                            .setVoucherId(snapshot.getVoucherId())
                            .setUserId(snapshot.getUserId())
                            .setLogType(ReconcileLogType.FAIL.name())
                            .setSource("RECONCILE_ORPHAN_TRACE")
                            .setDetail("orphan redis trace rolled back")
                            .setBeforeQty(snapshot.getBeforeQty())
                            .setChangeQty(snapshot.getChangeQty())
                            .setAfterQty(snapshot.getAfterQty())
                            .setReconciliationStatus(ReconciliationStatus.ABNORMAL.name())
                            .setCreatedTime(LocalDateTime.now())
                            .setUpdateTime(LocalDateTime.now()));
                    redisTraceService.deleteTrace(voucherId, snapshot.getTraceId());
                    benchmarkMetricsService.incrementReconcileAbnormal();
                }
            }
        }
    }

    private void rebuildMissingRedisState() {
        List<VoucherOrder> activeOrders = voucherOrderMapper.selectList(new QueryWrapper<VoucherOrder>()
                .ne("status", 4)
                .orderByAsc("id")
                .last("LIMIT 200"));
        for (VoucherOrder order : activeOrders) {
            ensureStockKey(order.getVoucherId());
            stringRedisTemplate.opsForSet().add(RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId(), String.valueOf(order.getUserId()));
            if (order.getReconciliationStatus() == null) {
                voucherOrderMapper.updateById(new VoucherOrder()
                        .setId(order.getId())
                        .setReconciliationStatus(ReconciliationStatus.CONSISTENT.name())
                        .setUpdateTime(LocalDateTime.now()));
            }
        }
    }

    private void ensureStockKey(Long voucherId) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            return;
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher != null && seckillVoucher.getStock() != null) {
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(seckillVoucher.getStock()));
            benchmarkMetricsService.incrementReconcileInconsistent();
        }
    }

    private String findMessageIdByTrace(Long traceId) {
        com.livepick.entity.VoucherReconcileLog log = voucherReconcileLogService.findByTraceId(traceId);
        return log == null ? null : log.getMessageId();
    }
}
