package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.config.KafkaTopicNames;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.service.CacheDeleteRetryScheduleService;
import com.livepick.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CacheDeleteRetryConsumerTest {

    @Mock
    private CacheClient cacheClient;

    @Mock
    private CacheDeleteRetryScheduleService cacheDeleteRetryScheduleService;
    @Mock
    private KafkaTopicNames kafkaTopicNames;
    @Mock
    private Acknowledgment acknowledgment;

    private LivPickProperties livPickProperties;

    private CacheDeleteRetryConsumer cacheDeleteRetryConsumer;

    @BeforeEach
    void setUp() {
        livPickProperties = new LivPickProperties();
        livPickProperties.getCache().setDeleteRetryMaxAttempts(3);
        cacheDeleteRetryConsumer = new CacheDeleteRetryConsumer(cacheClient, cacheDeleteRetryScheduleService, livPickProperties, kafkaTopicNames);
    }

    @Test
    void shouldScheduleRetryWhenDeleteFailsAndAttemptsRemain() {
        CacheDeleteRetryMessage message = buildMessage(0);
        doThrow(new RuntimeException("delete failed")).when(cacheClient).delete(message.getCacheKey());

        cacheDeleteRetryConsumer.consume(JSONUtil.toJsonStr(message), acknowledgment);

        ArgumentCaptor<CacheDeleteRetryMessage> captor = ArgumentCaptor.forClass(CacheDeleteRetryMessage.class);
        verify(cacheDeleteRetryScheduleService).schedule(captor.capture());
        assertEquals(1, captor.getValue().getRetryCount());
        assertEquals(message.getCacheKey(), captor.getValue().getCacheKey());
        assertEquals("delete failed", captor.getValue().getLastError());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldStopRetryWhenAttemptsExhausted() {
        CacheDeleteRetryMessage message = buildMessage(3);
        doThrow(new RuntimeException("delete failed")).when(cacheClient).delete(message.getCacheKey());

        cacheDeleteRetryConsumer.consume(JSONUtil.toJsonStr(message), acknowledgment);

        verify(cacheDeleteRetryScheduleService, never()).schedule(any());
        verify(acknowledgment).acknowledge();
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
