package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.service.IVoucherOrderService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeckillOrderConsumerTest {

    @Mock
    private IVoucherOrderService voucherOrderService;

    @Mock
    private BenchmarkMetricsService benchmarkMetricsService;

    @InjectMocks
    private SeckillOrderConsumer seckillOrderConsumer;

    @Test
    void shouldDelegateMessageToOrderService() {
        SeckillOrderMessage message = buildMessage();

        seckillOrderConsumer.consume(JSONUtil.toJsonStr(message));

        verify(voucherOrderService).createVoucherOrder(message);
    }

    @Test
    void shouldRethrowWhenOrderServiceFails() {
        SeckillOrderMessage message = buildMessage();
        doThrow(new IllegalStateException("boom")).when(voucherOrderService).createVoucherOrder(message);

        assertThrows(IllegalStateException.class, () -> seckillOrderConsumer.consume(JSONUtil.toJsonStr(message)));
        verify(voucherOrderService).createVoucherOrder(message);
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
