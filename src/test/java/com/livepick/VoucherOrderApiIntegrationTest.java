package com.livepick;

import cn.hutool.json.JSONUtil;
import com.livepick.entity.Voucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.SeckillReservationService;
import com.livepick.support.ApiTestSupport;
import com.livepick.utils.OrderStatusConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MvcResult;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.livepick.utils.RedisConstants.SECKILL_PENDING_SEND_INDEX_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_PENDING_SEND_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_REORDER_KEY;

@Transactional
@SpringBootTest
@AutoConfigureMockMvc
class VoucherOrderApiIntegrationTest extends ApiTestSupport {

    @MockBean
    private LivPickKafkaProducer livPickKafkaProducer;

    @Resource
    private SeckillReservationService seckillReservationService;

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
    void shouldAcceptSeckillAndKeepPendingMessageWhenKafkaSendFails() throws Exception {
        Long userId = 9530L;
        String token = prepareLoginToken(userId);
        Voucher voucher = createSeckillVoucherFixture("api-order-pending");
        clearSeckillReservation(voucher.getId(), userId);
        registerCleanupKey(SECKILL_PENDING_SEND_INDEX_KEY);
        doThrow(new TimeoutException("mock timeout")).when(livPickKafkaProducer).sendSeckillOrder(any(SeckillOrderMessage.class));

        MvcResult mvcResult = mockMvc.perform(post("/voucher-order/seckill/{id}", voucher.getId())
                        .header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn();

        Long orderId = JSONUtil.parseObj(mvcResult.getResponse().getContentAsString()).getLong("data");
        registerCleanupKey(SECKILL_PENDING_SEND_KEY + orderId);

        Set<String> pendingOrderIds = stringRedisTemplate.opsForZSet().range(SECKILL_PENDING_SEND_INDEX_KEY, 0, -1);
        org.junit.jupiter.api.Assertions.assertTrue(pendingOrderIds != null && pendingOrderIds.contains(String.valueOf(orderId)));
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

    @Test
    void shouldReuseCancelledOrderIdWhenUserSeckillsAgain() throws Exception {
        Long userId = 9531L;
        String token = prepareLoginToken(userId);
        Voucher voucher = createSeckillVoucherFixture("api-reorder");
        VoucherOrder cancelledOrder = createCancelledOrderFixture(voucher.getId(), userId);
        clearSeckillReservation(voucher.getId(), userId);
        registerCleanupKey(SECKILL_REORDER_KEY + voucher.getId() + ":" + userId);
        stringRedisTemplate.opsForValue().set(
                SECKILL_REORDER_KEY + voucher.getId() + ":" + userId,
                String.valueOf(cancelledOrder.getId())
        );

        MvcResult mvcResult = mockMvc.perform(post("/voucher-order/seckill/{id}", voucher.getId())
                        .header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        Long orderId = JSONUtil.parseObj(mvcResult.getResponse().getContentAsString()).getLong("data");
        org.junit.jupiter.api.Assertions.assertEquals(cancelledOrder.getId(), orderId);
    }

    @Test
    void shouldWriteReusableOrderMarkerAfterTimeoutRollbackScript() {
        Long voucherId = 701L;
        Long userId = 702L;
        Long orderId = 703L;
        String stockKey = com.livepick.utils.RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = com.livepick.utils.RedisConstants.SECKILL_ORDER_KEY + voucherId;
        String reorderKey = SECKILL_REORDER_KEY + voucherId + ":" + userId;
        registerCleanupKey(stockKey);
        registerCleanupKey(orderKey);
        registerCleanupKey(reorderKey);

        stringRedisTemplate.opsForValue().set(stockKey, "9");
        stringRedisTemplate.opsForSet().add(orderKey, String.valueOf(userId));

        seckillReservationService.rollbackReservationAfterTimeoutCancel(voucherId, userId, orderId);

        org.junit.jupiter.api.Assertions.assertEquals("10", stringRedisTemplate.opsForValue().get(stockKey));
        org.junit.jupiter.api.Assertions.assertFalse(Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(orderKey, String.valueOf(userId))
        ));
        org.junit.jupiter.api.Assertions.assertEquals(String.valueOf(orderId), stringRedisTemplate.opsForValue().get(reorderKey));
    }
}
