package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.KafkaTopicNames;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.IVoucherOrderService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderFailureRecoverer implements ConsumerRecordRecoverer {

    private final LivPickKafkaProducer livPickKafkaProducer;
    private final KafkaTopicNames kafkaTopicNames;
    private final IVoucherOrderService voucherOrderService;
    private final BenchmarkMetricsService benchmarkMetricsService;

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        SeckillOrderMessage message = JSONUtil.toBean(String.valueOf(record.value()), SeckillOrderMessage.class);
        voucherOrderService.handleSeckillOrderFailure(message, "KAFKA_RETRY_EXHAUSTED", exception.getMessage());
        benchmarkMetricsService.incrementConsumerRetryExhausted();
        livPickKafkaProducer.sendAsync(
                kafkaTopicNames.seckillOrderDlqTopic(),
                String.valueOf(message.getUserId()),
                String.valueOf(record.value()),
                benchmarkMetricsService::incrementKafkaDlqPublished,
                throwable -> log.error("publish seckill order message to dlq failed, orderId={}", message.getOrderId(), throwable)
        );
    }
}
