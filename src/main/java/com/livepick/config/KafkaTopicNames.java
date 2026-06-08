package com.livepick.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaTopicNames {

    private final LivPickProperties livPickProperties;

    public String seckillOrderTopic() {
        return prefixed(livPickProperties.getKafka().getSeckillOrderTopic());
    }

    public String seckillOrderDlqTopic() {
        return prefixed(livPickProperties.getKafka().getSeckillOrderDlqTopic());
    }

    public String cacheDeleteRetryTopic() {
        return prefixed(livPickProperties.getKafka().getCacheDeleteRetryTopic());
    }

    public String seckillOrderGroup() {
        return prefixed(livPickProperties.getKafka().getSeckillOrderGroup());
    }

    public String seckillOrderDlqGroup() {
        return prefixed(livPickProperties.getKafka().getSeckillOrderDlqGroup());
    }

    public String prefixed(String suffix) {
        return livPickProperties.getPrefixDistinctionName() + "-" + suffix;
    }
}
