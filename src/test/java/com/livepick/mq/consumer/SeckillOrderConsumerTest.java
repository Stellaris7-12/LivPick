package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.service.IVoucherOrderService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeckillOrderConsumerTest {

    @Mock
    private IVoucherOrderService voucherOrderService;

    @Mock
    private BenchmarkMetricsService benchmarkMetricsService;
    @Mock
    private BenchmarkRuntimeConfigService benchmarkRuntimeConfigService;
    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private SeckillOrderConsumer seckillOrderConsumer;

    @Test
    void shouldDelegateMessageToOrderService() {
        SeckillOrderMessage message = buildMessage();

        seckillOrderConsumer.consume(JSONUtil.toJsonStr(message), acknowledgment);

        verify(voucherOrderService).createVoucherOrder(message);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldRethrowWhenOrderServiceFails() {
        SeckillOrderMessage message = buildMessage();
        doThrow(new IllegalStateException("boom")).when(voucherOrderService).createVoucherOrder(message);

        assertThrows(IllegalStateException.class, () -> seckillOrderConsumer.consume(JSONUtil.toJsonStr(message), acknowledgment));
        verify(voucherOrderService).createVoucherOrder(message);
        verifyNoMoreInteractions(acknowledgment);
    }

    private SeckillOrderMessage buildMessage() {
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(1L);
        message.setUserId(2L);
        message.setVoucherId(3L);
        message.setCreateTime(LocalDateTime.of(2026, 5, 7, 16, 30));
        return message;
    }
}
