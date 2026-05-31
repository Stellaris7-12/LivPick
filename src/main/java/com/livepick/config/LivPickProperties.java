package com.livepick.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "livpick")
public class LivPickProperties {

    private Kafka kafka = new Kafka();
    private Seckill seckill = new Seckill();
    private Order order = new Order();
    private Cache cache = new Cache();
    private Bloom bloom = new Bloom();
    private Benchmark benchmark = new Benchmark();

    @Data
    public static class Kafka {
        private String seckillOrderTopic = "seckill-order-create";
        private String cacheDeleteRetryTopic = "cache-shop-delete-retry";
        private int partitions = 1;
        private short replicas = 1;
    }

    @Data
    public static class Seckill {
        private long pendingSendScanIntervalMs = 5_000L;
        private long pendingSendRetryDelayMs = 5_000L;
        private long pendingSendTtlMinutes = 20L;
        private int pendingSendMaxAttempts = 3;
        private int pendingSendBatchSize = 20;
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
    }
}
