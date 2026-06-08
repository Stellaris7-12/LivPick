package com.livepick.service.benchmark;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BenchmarkMetricsService {

    private static final int LATENCY_SAMPLE_LIMIT = 10_000;

    private final AtomicLong apiAccepted = new AtomicLong();
    private final AtomicLong luaStockRejected = new AtomicLong();
    private final AtomicLong luaDuplicateRejected = new AtomicLong();
    private final AtomicLong kafkaSendSuccess = new AtomicLong();
    private final AtomicLong kafkaSendFailure = new AtomicLong();
    private final AtomicLong kafkaDlqPublished = new AtomicLong();
    private final AtomicLong pendingRegistered = new AtomicLong();
    private final AtomicLong pendingRetried = new AtomicLong();
    private final AtomicLong pendingRollback = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxRetried = new AtomicLong();
    private final AtomicLong consumerCreated = new AtomicLong();
    private final AtomicLong consumerReactivated = new AtomicLong();
    private final AtomicLong consumerDuplicate = new AtomicLong();
    private final AtomicLong consumerFailure = new AtomicLong();
    private final AtomicLong consumerRetryExhausted = new AtomicLong();
    private final AtomicLong timeoutClosed = new AtomicLong();
    private final AtomicLong paySuccess = new AtomicLong();
    private final AtomicLong redisRollbackSuccess = new AtomicLong();
    private final AtomicLong redisRollbackFailure = new AtomicLong();
    private final AtomicLong reconcileAbnormal = new AtomicLong();
    private final AtomicLong reconcileInconsistent = new AtomicLong();
    private final AtomicLong cacheRequests = new AtomicLong();
    private final AtomicLong bloomRejected = new AtomicLong();
    private final AtomicLong bloomPassed = new AtomicLong();
    private final AtomicLong cacheNullHit = new AtomicLong();
    private final AtomicLong redisQuery = new AtomicLong();
    private final AtomicLong dbFallback = new AtomicLong();
    private final AtomicLong timeoutExpired = new AtomicLong();
    private final AtomicLong timeoutDelayQueueTriggered = new AtomicLong();
    private final AtomicLong timeoutFallbackTriggered = new AtomicLong();
    private final AtomicLong mqBacklogPeak = new AtomicLong();
    private final AtomicLong mqDrainCompletedAt = new AtomicLong();
    private final AtomicLong consumerPauseAccepted = new AtomicLong();
    private final AtomicLong stabilityRecoveryCompleted = new AtomicLong();
    private final AtomicLong metricsResetAt = new AtomicLong(System.currentTimeMillis());

    private final ConcurrentLinkedQueue<Long> cacheLatencySamples = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> timeoutLagSamples = new ConcurrentLinkedQueue<>();

    public void incrementCacheRequests() {
        cacheRequests.incrementAndGet();
    }

    public void incrementBloomRejected() {
        bloomRejected.incrementAndGet();
    }

    public void incrementBloomPassed() {
        bloomPassed.incrementAndGet();
    }

    public void incrementCacheNullHit() {
        cacheNullHit.incrementAndGet();
    }

    public void incrementRedisQuery() {
        redisQuery.incrementAndGet();
    }

    public void incrementDbFallback() {
        dbFallback.incrementAndGet();
    }

    public void recordCacheLatency(long latencyMs) {
        addSample(cacheLatencySamples, latencyMs);
    }

    public void incrementTimeoutExpired() {
        timeoutExpired.incrementAndGet();
    }

    public void incrementTimeoutDelayQueueTriggered() {
        timeoutDelayQueueTriggered.incrementAndGet();
    }

    public void incrementTimeoutFallbackTriggered() {
        timeoutFallbackTriggered.incrementAndGet();
    }

    public void recordTimeoutLag(long lagMs) {
        addSample(timeoutLagSamples, lagMs);
    }

    public void updateMqBacklog(long backlog) {
        mqBacklogPeak.accumulateAndGet(backlog, Math::max);
    }

    public void markMqDrained() {
        mqDrainCompletedAt.set(System.currentTimeMillis());
    }

    public void incrementConsumerPauseAccepted() {
        consumerPauseAccepted.incrementAndGet();
    }

    public void incrementStabilityRecoveryCompleted() {
        stabilityRecoveryCompleted.incrementAndGet();
    }

    public void incrementApiAccepted() {
        apiAccepted.incrementAndGet();
    }

    public void incrementLuaStockRejected() {
        luaStockRejected.incrementAndGet();
    }

    public void incrementLuaDuplicateRejected() {
        luaDuplicateRejected.incrementAndGet();
    }

    public void incrementKafkaSendSuccess() {
        kafkaSendSuccess.incrementAndGet();
    }

    public void incrementKafkaSendFailure() {
        kafkaSendFailure.incrementAndGet();
    }

    public void incrementKafkaDlqPublished() {
        kafkaDlqPublished.incrementAndGet();
    }

    public void incrementPendingRegistered() {
        pendingRegistered.incrementAndGet();
    }

    public void incrementPendingRetried() {
        pendingRetried.incrementAndGet();
    }

    public void incrementPendingRollback() {
        pendingRollback.incrementAndGet();
    }

    public void incrementOutboxPending() {
        outboxPending.incrementAndGet();
    }

    public void incrementOutboxRetried() {
        outboxRetried.incrementAndGet();
    }

    public void incrementConsumerCreated() {
        consumerCreated.incrementAndGet();
    }

    public void incrementConsumerReactivated() {
        consumerReactivated.incrementAndGet();
    }

    public void incrementConsumerDuplicate() {
        consumerDuplicate.incrementAndGet();
    }

    public void incrementConsumerFailure() {
        consumerFailure.incrementAndGet();
    }

    public void incrementConsumerRetryExhausted() {
        consumerRetryExhausted.incrementAndGet();
    }

    public void incrementTimeoutClosed() {
        timeoutClosed.incrementAndGet();
    }

    public void incrementPaySuccess() {
        paySuccess.incrementAndGet();
    }

    public void incrementRedisRollbackSuccess() {
        redisRollbackSuccess.incrementAndGet();
    }

    public void incrementRedisRollbackFailure() {
        redisRollbackFailure.incrementAndGet();
    }

    public void incrementReconcileAbnormal() {
        reconcileAbnormal.incrementAndGet();
    }

    public void incrementReconcileInconsistent() {
        reconcileInconsistent.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> seckill = new LinkedHashMap<>();
        seckill.put("apiAccepted", apiAccepted.get());
        seckill.put("luaStockRejected", luaStockRejected.get());
        seckill.put("luaDuplicateRejected", luaDuplicateRejected.get());
        seckill.put("kafkaSendSuccess", kafkaSendSuccess.get());
        seckill.put("kafkaSendFailure", kafkaSendFailure.get());
        seckill.put("kafkaDlqPublished", kafkaDlqPublished.get());
        seckill.put("pendingRegistered", pendingRegistered.get());
        seckill.put("pendingRetried", pendingRetried.get());
        seckill.put("pendingRollback", pendingRollback.get());
        seckill.put("outboxPending", outboxPending.get());
        seckill.put("outboxRetried", outboxRetried.get());
        seckill.put("consumerCreated", consumerCreated.get());
        seckill.put("consumerReactivated", consumerReactivated.get());
        seckill.put("consumerDuplicate", consumerDuplicate.get());
        seckill.put("consumerFailure", consumerFailure.get());
        seckill.put("consumerRetryExhausted", consumerRetryExhausted.get());
        seckill.put("timeoutClosed", timeoutClosed.get());
        seckill.put("paySuccess", paySuccess.get());
        seckill.put("redisRollbackSuccess", redisRollbackSuccess.get());
        seckill.put("redisRollbackFailure", redisRollbackFailure.get());
        seckill.put("reconcileAbnormal", reconcileAbnormal.get());
        seckill.put("reconcileInconsistent", reconcileInconsistent.get());
        root.put("seckill", seckill);

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("requests", cacheRequests.get());
        cache.put("bloomRejected", bloomRejected.get());
        cache.put("bloomPassed", bloomPassed.get());
        cache.put("cacheNullHit", cacheNullHit.get());
        cache.put("redisQuery", redisQuery.get());
        cache.put("dbFallback", dbFallback.get());
        cache.put("latencyAvgMs", average(cacheLatencySamples));
        cache.put("latencyP95Ms", percentile(cacheLatencySamples, 0.95));
        root.put("cache", cache);

        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("expired", timeoutExpired.get());
        timeout.put("delayQueueTriggered", timeoutDelayQueueTriggered.get());
        timeout.put("fallbackTriggered", timeoutFallbackTriggered.get());
        timeout.put("closeLagP50Ms", percentile(timeoutLagSamples, 0.50));
        timeout.put("closeLagP95Ms", percentile(timeoutLagSamples, 0.95));
        timeout.put("closeLagMaxMs", max(timeoutLagSamples));
        root.put("timeout", timeout);

        Map<String, Object> stability = new LinkedHashMap<>();
        stability.put("mqBacklogPeak", mqBacklogPeak.get());
        stability.put("mqDrainCompletedAt", mqDrainCompletedAt.get());
        stability.put("consumerPauseAccepted", consumerPauseAccepted.get());
        stability.put("stabilityRecoveryCompleted", stabilityRecoveryCompleted.get());
        stability.put("metricsResetAt", metricsResetAt.get());
        root.put("stability", stability);
        return root;
    }

    public void reset() {
        apiAccepted.set(0);
        luaStockRejected.set(0);
        luaDuplicateRejected.set(0);
        kafkaSendSuccess.set(0);
        kafkaSendFailure.set(0);
        kafkaDlqPublished.set(0);
        pendingRegistered.set(0);
        pendingRetried.set(0);
        pendingRollback.set(0);
        outboxPending.set(0);
        outboxRetried.set(0);
        consumerCreated.set(0);
        consumerReactivated.set(0);
        consumerDuplicate.set(0);
        consumerFailure.set(0);
        consumerRetryExhausted.set(0);
        timeoutClosed.set(0);
        paySuccess.set(0);
        redisRollbackSuccess.set(0);
        redisRollbackFailure.set(0);
        reconcileAbnormal.set(0);
        reconcileInconsistent.set(0);
        cacheRequests.set(0);
        bloomRejected.set(0);
        bloomPassed.set(0);
        cacheNullHit.set(0);
        redisQuery.set(0);
        dbFallback.set(0);
        timeoutExpired.set(0);
        timeoutDelayQueueTriggered.set(0);
        timeoutFallbackTriggered.set(0);
        mqBacklogPeak.set(0);
        mqDrainCompletedAt.set(0);
        consumerPauseAccepted.set(0);
        stabilityRecoveryCompleted.set(0);
        metricsResetAt.set(System.currentTimeMillis());
        cacheLatencySamples.clear();
        timeoutLagSamples.clear();
    }

    private void addSample(ConcurrentLinkedQueue<Long> samples, long value) {
        if (samples.size() >= LATENCY_SAMPLE_LIMIT) {
            samples.poll();
        }
        samples.offer(Math.max(0L, value));
    }

    private double average(ConcurrentLinkedQueue<Long> samples) {
        if (samples.isEmpty()) {
            return 0D;
        }
        long total = 0L;
        int count = 0;
        for (Long value : samples) {
            total += value;
            count++;
        }
        return count == 0 ? 0D : Math.round((total * 100.0D) / count) / 100.0D;
    }

    private long percentile(ConcurrentLinkedQueue<Long> samples, double percentile) {
        if (samples.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }
        return sorted.get(index);
    }

    private long max(ConcurrentLinkedQueue<Long> samples) {
        long max = 0L;
        for (Long value : samples) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }
}
