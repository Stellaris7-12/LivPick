package com.livepick.task;

import com.livepick.config.LivPickProperties;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.mq.message.PendingSeckillOrderMessage;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.SeckillPendingSendService;
import com.livepick.service.SeckillReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillPendingRetryTask {

    private final SeckillPendingSendService pendingSendService;
    private final VoucherOrderMapper voucherOrderMapper;
    private final LivPickKafkaProducer livPickKafkaProducer;
    private final SeckillReservationService seckillReservationService;
    private final LivPickProperties livPickProperties;

    @Scheduled(fixedDelayString = "${livpick.seckill.pending-send-scan-interval-ms}")
    public void retryPendingMessages() {
        while (true) {
            List<PendingSeckillOrderMessage> dueMessages = pendingSendService.pollDueMessages();
            if (dueMessages.isEmpty()) {
                return;
            }
            dueMessages.forEach(this::retrySingleMessage);
        }
    }

    private void retrySingleMessage(PendingSeckillOrderMessage pendingMessage) {
        VoucherOrder existingOrder = voucherOrderMapper.selectById(pendingMessage.getOrderId());
        if (existingOrder != null) {
            pendingSendService.clear(pendingMessage.getOrderId());
            return;
        }

        try {
            livPickKafkaProducer.sendSeckillOrder(toSeckillOrderMessage(pendingMessage));
            pendingSendService.clear(pendingMessage.getOrderId());
        } catch (Exception e) {
            int nextRetryCount = pendingMessage.getRetryCount() + 1;
            if (nextRetryCount > livPickProperties.getSeckill().getPendingSendMaxAttempts()) {
                VoucherOrder orderAfterFailure = voucherOrderMapper.selectById(pendingMessage.getOrderId());
                if (orderAfterFailure == null) {
                    seckillReservationService.rollbackReservationBeforeOrderCreated(
                            pendingMessage.getVoucherId(),
                            pendingMessage.getUserId()
                    );
                }
                pendingSendService.clear(pendingMessage.getOrderId());
                log.error("pending seckill message retry exhausted, orderId={}", pendingMessage.getOrderId(), e);
                return;
            }
            pendingMessage.setRetryCount(nextRetryCount);
            pendingMessage.setNextRetryAt(System.currentTimeMillis() + livPickProperties.getSeckill().getPendingSendRetryDelayMs());
            pendingSendService.save(pendingMessage);
            log.warn("retry pending seckill message later, orderId={}, retryCount={}",
                    pendingMessage.getOrderId(), pendingMessage.getRetryCount(), e);
        }
    }

    private SeckillOrderMessage toSeckillOrderMessage(PendingSeckillOrderMessage pendingMessage) {
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(pendingMessage.getOrderId());
        message.setUserId(pendingMessage.getUserId());
        message.setVoucherId(pendingMessage.getVoucherId());
        message.setCreateTime(pendingMessage.getCreateTime());
        return message;
    }
}
