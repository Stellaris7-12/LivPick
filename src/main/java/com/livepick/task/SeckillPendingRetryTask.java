package com.livepick.task;

import com.livepick.config.LivPickProperties;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.mq.message.PendingSeckillOrderMessage;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.SeckillPendingSendService;
import com.livepick.service.SeckillReservationService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.utils.OrderStatusConstants;
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
    private final BenchmarkMetricsService benchmarkMetricsService;

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
        if (existingOrder != null && existingOrder.getStatus() != null
                && existingOrder.getStatus() != OrderStatusConstants.CANCELLED) {
            pendingSendService.clear(pendingMessage.getOrderId());
            return;
        }

        VoucherOrder userVoucherOrder = voucherOrderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<VoucherOrder>()
                        .eq("user_id", pendingMessage.getUserId())
                        .eq("voucher_id", pendingMessage.getVoucherId())
                        .last("LIMIT 1")
        );
        if (userVoucherOrder != null
                && userVoucherOrder.getStatus() != null
                && userVoucherOrder.getStatus() != OrderStatusConstants.CANCELLED) {
            pendingSendService.clear(pendingMessage.getOrderId());
            return;
        }

        try {
            benchmarkMetricsService.incrementPendingRetried();
            livPickKafkaProducer.sendSeckillOrder(toSeckillOrderMessage(pendingMessage));
            pendingSendService.clear(pendingMessage.getOrderId());
        } catch (Exception e) {
            int nextRetryCount = pendingMessage.getRetryCount() + 1;
            if (nextRetryCount > livPickProperties.getSeckill().getPendingSendMaxAttempts()) {
                VoucherOrder orderAfterFailure = voucherOrderMapper.selectById(pendingMessage.getOrderId());
                if (orderAfterFailure == null || orderAfterFailure.getStatus() == OrderStatusConstants.CANCELLED) {
                    seckillReservationService.rollbackReservationBeforeOrderCreated(
                            pendingMessage.getVoucherId(),
                            pendingMessage.getUserId()
                    );
                    benchmarkMetricsService.incrementPendingRollback();
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
