package com.livepick.mq.producer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.KafkaTopicNames;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class LivPickKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final KafkaTopicNames kafkaTopicNames;

    public void sendCacheDeleteRetry(CacheDeleteRetryMessage message) {
        sendAsync(
                kafkaTopicNames.cacheDeleteRetryTopic(),
                message.getCacheKey(),
                JSONUtil.toJsonStr(message),
                null,
                null
        );
    }

    public void sendAsync(String topic, String key, String payload,
                          Runnable onSuccess, Consumer<Throwable> onFailure) {
        ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);
        future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
            @Override
            public void onFailure(Throwable ex) {
                benchmarkMetricsService.incrementKafkaSendFailure();
                if (onFailure != null) {
                    onFailure.accept(ex);
                }
            }

            @Override
            public void onSuccess(SendResult<String, String> result) {
                benchmarkMetricsService.incrementKafkaSendSuccess();
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }
        });
    }
}
