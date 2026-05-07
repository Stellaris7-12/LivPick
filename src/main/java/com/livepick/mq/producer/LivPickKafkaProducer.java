package com.livepick.mq.producer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.mq.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class LivPickKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LivPickProperties livPickProperties;

    public void sendSeckillOrder(SeckillOrderMessage message)
            throws ExecutionException, InterruptedException, TimeoutException {
        kafkaTemplate.send(
                        livPickProperties.getKafka().getSeckillOrderTopic(),
                        String.valueOf(message.getOrderId()),
                        JSONUtil.toJsonStr(message)
                )
                .get(5, TimeUnit.SECONDS);
    }

    public void sendCacheDeleteRetry(CacheDeleteRetryMessage message)
            throws ExecutionException, InterruptedException, TimeoutException {
        kafkaTemplate.send(
                        livPickProperties.getKafka().getCacheDeleteRetryTopic(),
                        message.getCacheKey(),
                        JSONUtil.toJsonStr(message)
                )
                .get(5, TimeUnit.SECONDS);
    }
}
