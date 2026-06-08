package com.livepick.task;

import com.livepick.config.LivPickProperties;
import com.livepick.entity.MqOutboxMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.MqOutboxService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatchTask {

    private final MqOutboxService mqOutboxService;
    private final LivPickKafkaProducer livPickKafkaProducer;
    private final LivPickProperties livPickProperties;
    private final BenchmarkMetricsService benchmarkMetricsService;

    @Scheduled(fixedDelayString = "${livpick.kafka.outbox-scan-interval-ms}")
    public void dispatch() {
        List<MqOutboxMessage> dispatchable = mqOutboxService.pollDispatchable(50);
        if (dispatchable.isEmpty()) {
            return;
        }
        benchmarkMetricsService.updateMqBacklog(dispatchable.size());
        for (MqOutboxMessage message : dispatchable) {
            if (!mqOutboxService.markSending(message.getId())) {
                continue;
            }
            livPickKafkaProducer.sendAsync(
                    message.getTopic(),
                    message.getMessageKey(),
                    message.getPayload(),
                    () -> mqOutboxService.markSent(message.getId()),
                    ex -> {
                        benchmarkMetricsService.incrementOutboxRetried();
                        mqOutboxService.markFailedOrDlq(
                                message,
                                livPickProperties.getKafka().getOutboxMaxAttempts(),
                                nextBackoffMs(message.getRetryCount()),
                                ex.getMessage()
                        );
                    }
            );
        }
    }

    private long nextBackoffMs(Integer retryCount) {
        long backoffMs = livPickProperties.getKafka().getOutboxInitialBackoffMs();
        int currentRetry = retryCount == null ? 0 : retryCount;
        for (int i = 0; i < currentRetry; i++) {
            backoffMs = Math.min(backoffMs * 2, livPickProperties.getKafka().getOutboxMaxBackoffMs());
        }
        return backoffMs;
    }
}
