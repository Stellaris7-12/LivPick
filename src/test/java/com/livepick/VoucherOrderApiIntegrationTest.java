package com.livepick;

import com.livepick.entity.Voucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.support.ApiTestSupport;
import com.livepick.utils.OrderStatusConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@SpringBootTest
@AutoConfigureMockMvc
class VoucherOrderApiIntegrationTest extends ApiTestSupport {

    @MockBean
    private LivPickKafkaProducer livPickKafkaProducer;

    @Test
    void shouldSeckillVoucherAndSendKafkaMessage() throws Exception {
        Long userId = 9527L;
        String token = prepareLoginToken(userId);
        Voucher voucher = createSeckillVoucherFixture("api-order");
        clearSeckillReservation(voucher.getId(), userId);

        mockMvc.perform(post("/voucher-order/seckill/{id}", voucher.getId())
                        .header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNumber());

        verify(livPickKafkaProducer).sendSeckillOrder(any(SeckillOrderMessage.class));
    }

    @Test
    void shouldPayUnpaidOrder() throws Exception {
        Long userId = 9528L;
        String token = prepareLoginToken(userId);
        Voucher voucher = createSeckillVoucherFixture("api-pay");
        VoucherOrder order = createUnpaidOrderFixture(voucher.getId(), userId);

        mockMvc.perform(post("/voucher-order/pay/{id}", order.getId())
                        .header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void shouldFailWhenPayingNonUnpaidOrder() throws Exception {
        Long userId = 9529L;
        String token = prepareLoginToken(userId);
        Voucher voucher = createSeckillVoucherFixture("api-paid");
        VoucherOrder order = createUnpaidOrderFixture(voucher.getId(), userId);
        order.setStatus(OrderStatusConstants.PAID);
        voucherOrderMapper.updateById(order);

        mockMvc.perform(post("/voucher-order/pay/{id}", order.getId())
                        .header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").isNotEmpty());
    }
}
