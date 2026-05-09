package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CacheDeleteRetryConsumerTest {

    @Mock
    private CacheClient cacheClient;

    @Mock
    private LivPickKafkaProducer livPickKafkaProducer;

    private LivPickProperties livPickProperties;

    private CacheDeleteRetryConsumer cacheDeleteRetryConsumer;

    @BeforeEach
    void setUp() {
        livPickProperties = new LivPickProperties();
        livPickProperties.getCache().setDeleteRetryMaxAttempts(3);
        cacheDeleteRetryConsumer = new CacheDeleteRetryConsumer(cacheClient, livPickKafkaProducer, livPickProperties);
    }

    @Test
    void shouldRetryWhenDeleteFailsAndAttemptsRemain() throws Exception {
        CacheDeleteRetryMessage message = buildMessage(0);
        doThrow(new RuntimeException("delete failed")).when(cacheClient).delete(message.getCacheKey());

        cacheDeleteRetryConsumer.consume(JSONUtil.toJsonStr(message));

        ArgumentCaptor<CacheDeleteRetryMessage> captor = ArgumentCaptor.forClass(CacheDeleteRetryMessage.class);
        verify(livPickKafkaProducer).sendCacheDeleteRetry(captor.capture());
        assertEquals(1, captor.getValue().getRetryCount());
        assertEquals(message.getCacheKey(), captor.getValue().getCacheKey());
    }

    @Test
    void shouldStopRetryWhenAttemptsExhausted() throws Exception {
        CacheDeleteRetryMessage message = buildMessage(3);
        doThrow(new RuntimeException("delete failed")).when(cacheClient).delete(message.getCacheKey());

        cacheDeleteRetryConsumer.consume(JSONUtil.toJsonStr(message));

        verify(livPickKafkaProducer, never()).sendCacheDeleteRetry(org.mockito.ArgumentMatchers.any());
    }

    private CacheDeleteRetryMessage buildMessage(int retryCount) {
        CacheDeleteRetryMessage message = new CacheDeleteRetryMessage();
        message.setCacheKey("cache:shop:1");
        message.setBizType("SHOP");
        message.setBizId(1L);
        message.setRetryCount(retryCount);
        return message;
    }
}
