package com.livepick.service.benchmark;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BenchmarkMetricsService {

    private final AtomicLong apiAccepted = new AtomicLong();
    private final AtomicLong luaStockRejected = new AtomicLong();
    private final AtomicLong luaDuplicateRejected = new AtomicLong();
    private final AtomicLong kafkaSendSuccess = new AtomicLong();
    private final AtomicLong kafkaSendFailure = new AtomicLong();
    private final AtomicLong pendingRegistered = new AtomicLong();
    private final AtomicLong pendingRetried = new AtomicLong();
    private final AtomicLong pendingRollback = new AtomicLong();
    private final AtomicLong consumerCreated = new AtomicLong();
    private final AtomicLong consumerReactivated = new AtomicLong();
    private final AtomicLong consumerDuplicate = new AtomicLong();
    private final AtomicLong consumerFailure = new AtomicLong();
    private final AtomicLong timeoutClosed = new AtomicLong();
    private final AtomicLong paySuccess = new AtomicLong();

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

    public void incrementPendingRegistered() {
        pendingRegistered.incrementAndGet();
    }

    public void incrementPendingRetried() {
        pendingRetried.incrementAndGet();
    }

    public void incrementPendingRollback() {
        pendingRollback.incrementAndGet();
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

    public void incrementTimeoutClosed() {
        timeoutClosed.incrementAndGet();
    }

    public void incrementPaySuccess() {
        paySuccess.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> seckill = new LinkedHashMap<>();
        seckill.put("apiAccepted", apiAccepted.get());
        seckill.put("luaStockRejected", luaStockRejected.get());
        seckill.put("luaDuplicateRejected", luaDuplicateRejected.get());
        seckill.put("kafkaSendSuccess", kafkaSendSuccess.get());
        seckill.put("kafkaSendFailure", kafkaSendFailure.get());
        seckill.put("pendingRegistered", pendingRegistered.get());
        seckill.put("pendingRetried", pendingRetried.get());
        seckill.put("pendingRollback", pendingRollback.get());
        seckill.put("consumerCreated", consumerCreated.get());
        seckill.put("consumerReactivated", consumerReactivated.get());
        seckill.put("consumerDuplicate", consumerDuplicate.get());
        seckill.put("consumerFailure", consumerFailure.get());
        seckill.put("timeoutClosed", timeoutClosed.get());
        seckill.put("paySuccess", paySuccess.get());
        root.put("seckill", seckill);
        return root;
    }

    public void reset() {
        apiAccepted.set(0);
        luaStockRejected.set(0);
        luaDuplicateRejected.set(0);
        kafkaSendSuccess.set(0);
        kafkaSendFailure.set(0);
        pendingRegistered.set(0);
        pendingRetried.set(0);
        pendingRollback.set(0);
        consumerCreated.set(0);
        consumerReactivated.set(0);
        consumerDuplicate.set(0);
        consumerFailure.set(0);
        timeoutClosed.set(0);
        paySuccess.set(0);
    }
}
