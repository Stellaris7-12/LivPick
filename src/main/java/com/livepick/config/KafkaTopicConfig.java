package com.livepick.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@RequiredArgsConstructor
public class KafkaTopicConfig {

    private final LivPickProperties livPickProperties;

    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(buildPrefixedTopic(livPickProperties.getKafka().getSeckillOrderTopic()))
                .partitions(livPickProperties.getKafka().getPartitions())
                .replicas(livPickProperties.getKafka().getReplicas())
                .build();
    }

    @Bean
    public NewTopic seckillOrderDlqTopic() {
        return TopicBuilder.name(buildPrefixedTopic(livPickProperties.getKafka().getSeckillOrderDlqTopic()))
                .partitions(livPickProperties.getKafka().getPartitions())
                .replicas(livPickProperties.getKafka().getReplicas())
                .build();
    }

    @Bean
    public NewTopic cacheDeleteRetryTopic() {
        return TopicBuilder.name(buildPrefixedTopic(livPickProperties.getKafka().getCacheDeleteRetryTopic()))
                .partitions(livPickProperties.getKafka().getPartitions())
                .replicas(livPickProperties.getKafka().getReplicas())
                .build();
    }

    private String buildPrefixedTopic(String topic) {
        return livPickProperties.getPrefixDistinctionName() + "-" + topic;
    }
}
