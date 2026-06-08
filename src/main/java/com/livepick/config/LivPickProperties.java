package com.livepick.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "livpick")
public class LivPickProperties {

    private String prefixDistinctionName = "livpick";
    private Kafka kafka = new Kafka();
    private Order order = new Order();
    private Cache cache = new Cache();
    private Bloom bloom = new Bloom();
    private Benchmark benchmark = new Benchmark();
    private RedisRollback redisRollback = new RedisRollback();
    private Reconcile reconcile = new Reconcile();

    @Data
    public static class Kafka {
        private String seckillOrderTopic = "seckill-order-create";
        private String seckillOrderDlqTopic = "seckill-order-create.DLQ";
        private String seckillOrderGroup = "livpick-seckill-order";
        private String seckillOrderDlqGroup = "livpick-seckill-order-dlq";
        private String cacheDeleteRetryTopic = "cache-shop-delete-retry";
        private int partitions = 1;
        private short replicas = 1;
        private long outboxScanIntervalMs = 3_000L;
        private int outboxMaxAttempts = 5;
        private long outboxInitialBackoffMs = 500L;
        private long outboxMaxBackoffMs = 10_000L;
    }

    @Data
    public static class Order {
        private long timeoutMinutes = 15L;
        private long timeoutScanIntervalMs = 60_000L;
        private int timeoutScanBatchSize = 100;
        private String delayQueueName = "order:timeout:queue";
    }

    @Data
    public static class Cache {
        private int deleteRetryMaxAttempts = 3;
        private long deleteRetryInitialDelayMs = 5_000L;
        private long deleteRetryMaxDelayMs = 60_000L;
        private long deleteRetryScanIntervalMs = 5_000L;
        private long deleteRetryMessageTtlMinutes = 60L;
        private int deleteRetryBatchSize = 20;
    }

    @Data
    public static class Bloom {
        private String shopFilterName = "bf:shop:id";
        private long expectedInsertions = 100_000L;
        private double falseProbability = 0.03D;
    }

    @Data
    public static class Benchmark {
        private boolean enabled = false;
        private boolean skipLoginCheck = false;
        private boolean authBypassEnabled = false;
        private String cachePenetrationMode = "BLOOM_NULL";
        private String timeoutMode = "DELAY_QUEUE_FALLBACK";
        private long orderTimeoutSecondsOverride = -1L;
    }

    @Data
    public static class RedisRollback {
        private int maxAttempts = 3;
        private long initialBackoffMs = 200L;
        private long maxBackoffMs = 1_000L;
    }

    @Data
    public static class Reconcile {
        private long scanIntervalMs = 15_000L;
        private long gracePeriodMs = 15_000L;
        private long traceFallbackTtlSeconds = 86_400L;
    }
}
