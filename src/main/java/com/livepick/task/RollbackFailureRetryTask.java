package com.livepick.task;

import com.livepick.entity.RollbackFailureLog;
import com.livepick.service.RedisReservationRecoveryService;
import com.livepick.service.RollbackFailureLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RollbackFailureRetryTask {

    private final RollbackFailureLogService rollbackFailureLogService;
    private final RedisReservationRecoveryService redisReservationRecoveryService;

    @Scheduled(fixedDelayString = "${livpick.reconcile.scan-interval-ms}")
    public void retryOpenFailures() {
        List<RollbackFailureLog> failures = rollbackFailureLogService.findOpenFailures(20);
        for (RollbackFailureLog failure : failures) {
            boolean success = redisReservationRecoveryService.rollbackBeforeOrderCreated(
                    failure.getVoucherId(),
                    failure.getUserId(),
                    failure.getOrderId(),
                    failure.getTraceId(),
                    "ROLLBACK_FAILURE_RETRY"
            );
            if (success) {
                rollbackFailureLogService.markResolved(failure.getId());
            } else {
                rollbackFailureLogService.markRetried(
                        failure.getId(),
                        (failure.getRetryAttempts() == null ? 0 : failure.getRetryAttempts()) + 1,
                        "retry failed"
                );
                log.warn("rollback failure retry still failing, orderId={}, traceId={}", failure.getOrderId(), failure.getTraceId());
            }
        }
    }
}
