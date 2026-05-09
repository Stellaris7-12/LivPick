package com.livepick.mq.consumer;

import cn.hutool.json.JSONUtil;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final IVoucherOrderService voucherOrderService;

    @KafkaListener(topics = "${livpick.kafka.seckill-order-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String messageJson) {
        SeckillOrderMessage message = JSONUtil.toBean(messageJson, SeckillOrderMessage.class);
        try {
            log.debug("consume seckill order message, orderId={}, userId={}, voucherId={}",
                    message.getOrderId(), message.getUserId(), message.getVoucherId());
            voucherOrderService.createVoucherOrder(message);
        } catch (Exception e) {
            log.error("consume seckill order message failed, orderId={}, userId={}, voucherId={}, failureStage=createVoucherOrder",
                    message.getOrderId(), message.getUserId(), message.getVoucherId(), e);
            throw e;
        }
    }
}
