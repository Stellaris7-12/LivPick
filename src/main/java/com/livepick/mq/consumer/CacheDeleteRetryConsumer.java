package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.KafkaTopicNames;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.service.CacheDeleteRetryScheduleService;
import com.livepick.utils.CacheClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheDeleteRetryConsumer {

    private final CacheClient cacheClient;
    private final CacheDeleteRetryScheduleService cacheDeleteRetryScheduleService;
    private final LivPickProperties livPickProperties;
    private final KafkaTopicNames kafkaTopicNames;

    @KafkaListener(
            topics = "#{@kafkaTopicNames.cacheDeleteRetryTopic()}",
            groupId = "#{@kafkaTopicNames.seckillOrderGroup()}",
            containerFactory = "cacheDeleteKafkaListenerContainerFactory"
    )
    public void consume(String messageJson, Acknowledgment acknowledgment) {
        CacheDeleteRetryMessage message = JSONUtil.toBean(messageJson, CacheDeleteRetryMessage.class);
        try {
            cacheClient.delete(message.getCacheKey());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            int nextRetryCount = message.getRetryCount() + 1;
            if (nextRetryCount > livPickProperties.getCache().getDeleteRetryMaxAttempts()) {
                log.error("cache delete retry exhausted, key={}", message.getCacheKey(), e);
                acknowledgment.acknowledge();
                return;
            }
            message.setRetryCount(nextRetryCount);
            message.setLastError(e.getMessage());
            cacheDeleteRetryScheduleService.schedule(message);
            acknowledgment.acknowledge();
        }
    }
}
