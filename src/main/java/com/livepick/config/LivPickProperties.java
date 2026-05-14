package com.livepick.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "livpick")
public class LivPickProperties {

    private Kafka kafka = new Kafka();
    private Order order = new Order();
    private Cache cache = new Cache();
    private Bloom bloom = new Bloom();

    @Data
    public static class Kafka { // Kafka相关参数配置
        private String seckillOrderTopic = "seckill-order-create";
        private String cacheDeleteRetryTopic = "cache-shop-delete-retry";
        private int partitions = 1;
        private short replicas = 1;
    }

    @Data
    public static class Order {  // 超时关单参数配置
        private long timeoutMinutes = 15L;
        private long timeoutScanIntervalMs = 60_000L;
        private String delayQueueName = "order:timeout:queue";
    }

    @Data
    public static class Cache { // 缓存删除重试参数配置
        private int deleteRetryMaxAttempts = 3;
    }

    @Data
    public static class Bloom { // 布隆过滤器参数配置
        private String shopFilterName = "bf:shop:id";
        private long expectedInsertions = 100_000L;
        private double falseProbability = 0.03D;
    }
}
