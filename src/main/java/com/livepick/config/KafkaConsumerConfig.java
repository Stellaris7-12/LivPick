package com.livepick.config;

import com.livepick.mq.consumer.SeckillOrderFailureRecoverer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final ConsumerFactory<String, String> consumerFactory;
    private final SeckillOrderFailureRecoverer seckillOrderFailureRecoverer;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> seckillKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setErrorHandler(new SeekToCurrentErrorHandler(seckillOrderFailureRecoverer, new FixedBackOff(1_000L, 2L)));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> cacheDeleteKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
