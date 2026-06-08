package com.livepick.service;

import com.livepick.config.LivPickProperties;
import com.livepick.entity.RollbackFailureLog;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.utils.RedisIdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisReservationRecoveryService {

    private final SeckillReservationService seckillReservationService;
    private final RollbackFailureLogService rollbackFailureLogService;
    private final LivPickProperties livPickProperties;
    private final RedisIdWorker redisIdWorker;
    private final BenchmarkMetricsService benchmarkMetricsService;

    public boolean rollbackBeforeOrderCreated(Long voucherId, Long userId, Long orderId, Long traceId, String source) {
        return executeRollback(voucherId, userId, orderId, traceId, source, false);
    }

    public boolean rollbackAfterTimeout(Long voucherId, Long userId, Long orderId, Long traceId, String source) {
        return executeRollback(voucherId, userId, orderId, traceId, source, true);
    }

    private boolean executeRollback(Long voucherId, Long userId, Long orderId, Long traceId,
                                    String source, boolean timeoutRollback) {
        int attempts = 0;
        long backoffMs = livPickProperties.getRedisRollback().getInitialBackoffMs();
        while (attempts < livPickProperties.getRedisRollback().getMaxAttempts()) {
            attempts++;
            try {
                if (timeoutRollback) {
                    seckillReservationService.rollbackReservationAfterTimeoutCancel(voucherId, userId, orderId);
                } else {
                    seckillReservationService.rollbackReservationBeforeOrderCreated(voucherId, userId);
                }
                benchmarkMetricsService.incrementRedisRollbackSuccess();
                return true;
            } catch (Exception e) {
                log.warn("redis rollback failed, attempt={}, orderId={}, source={}", attempts, orderId, source, e);
                if (attempts >= livPickProperties.getRedisRollback().getMaxAttempts()) {
                    rollbackFailureLogService.save(new RollbackFailureLog()
                            .setId(redisIdWorker.nextId("rollback-failure"))
                            .setTraceId(traceId)
                            .setOrderId(orderId)
                            .setVoucherId(voucherId)
                            .setUserId(userId)
                            .setResultCode(-1)
                            .setRetryAttempts(attempts)
                            .setSource(source)
                            .setDetail(e.getMessage())
                            .setStatus("OPEN")
                            .setCreatedTime(LocalDateTime.now())
                            .setUpdateTime(LocalDateTime.now()));
                    benchmarkMetricsService.incrementRedisRollbackFailure();
                    return false;
                }
                sleepQuietly(backoffMs);
                backoffMs = Math.min(backoffMs * 2, livPickProperties.getRedisRollback().getMaxBackoffMs());
            }
        }
        return false;
    }

    private void sleepQuietly(long backoffMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
