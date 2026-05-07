package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.utils.CacheClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheDeleteRetryConsumer {

    private final CacheClient cacheClient;
    private final LivPickKafkaProducer livPickKafkaProducer;
    private final LivPickProperties livPickProperties;

    @KafkaListener(topics = "${livpick.kafka.cache-delete-retry-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String messageJson) {
        CacheDeleteRetryMessage message = JSONUtil.toBean(messageJson, CacheDeleteRetryMessage.class);
        try {
            cacheClient.delete(message.getCacheKey());
        } catch (Exception e) {
            int nextRetryCount = message.getRetryCount() + 1;
            if (nextRetryCount > livPickProperties.getCache().getDeleteRetryMaxAttempts()) {
                log.error("cache delete retry exhausted, key={}", message.getCacheKey(), e);
                return;
            }
            message.setRetryCount(nextRetryCount);
            try {
                livPickKafkaProducer.sendCacheDeleteRetry(message);
            } catch (Exception producerException) {
                log.error("resend cache delete retry message failed, key={}", message.getCacheKey(), producerException);
            }
        }
    }
}
