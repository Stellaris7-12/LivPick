package com.livepick.service;

import cn.hutool.json.JSONUtil;
import com.livepick.config.KafkaTopicNames;
import com.livepick.entity.MqOutboxMessage;
import com.livepick.entity.SeckillVoucher;
import com.livepick.entity.VoucherReconcileLog;
import com.livepick.enums.OutboxBizType;
import com.livepick.enums.OutboxStatus;
import com.livepick.enums.ReconcileLogType;
import com.livepick.enums.ReconciliationStatus;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.utils.RedisIdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class SeckillMessageAssembler {

    private final RedisIdWorker redisIdWorker;
    private final KafkaTopicNames kafkaTopicNames;

    public SeckillAcceptContext buildAcceptContext(Long traceId, Long orderId, Long userId, Long voucherId,
                                                   int beforeQty, int changeQty, int afterQty,
                                                   SeckillVoucher seckillVoucher, LocalDateTime createTime) {
        String messageId = String.valueOf(redisIdWorker.nextId("message"));
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setMessageId(messageId);
        message.setTraceId(traceId);
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        message.setBeforeQty(beforeQty);
        message.setChangeQty(changeQty);
        message.setAfterQty(afterQty);
        message.setCreateTime(createTime);

        LocalDateTime now = LocalDateTime.now();
        MqOutboxMessage outboxMessage = new MqOutboxMessage()
                .setId(redisIdWorker.nextId("outbox"))
                .setMessageId(messageId)
                .setBizType(OutboxBizType.SECKILL_ORDER.name())
                .setBizKey(String.valueOf(orderId))
                .setTopic(kafkaTopicNames.seckillOrderTopic())
                .setMessageKey(String.valueOf(userId))
                .setPayload(JSONUtil.toJsonStr(message))
                .setStatus(OutboxStatus.PENDING.name())
                .setRetryCount(0)
                .setNextRetryAt(now)
                .setCreatedTime(now)
                .setUpdateTime(now);

        VoucherReconcileLog reconcileLog = new VoucherReconcileLog()
                .setId(redisIdWorker.nextId("reconcile"))
                .setMessageId(messageId)
                .setTraceId(traceId)
                .setOrderId(orderId)
                .setVoucherId(voucherId)
                .setUserId(userId)
                .setLogType(ReconcileLogType.DEDUCT.name())
                .setSource("SECKILL_API")
                .setDetail("accepted and persisted to outbox")
                .setBeforeQty(beforeQty)
                .setChangeQty(changeQty)
                .setAfterQty(afterQty)
                .setReconciliationStatus(ReconciliationStatus.PENDING.name())
                .setCreatedTime(now)
                .setUpdateTime(now);

        return new SeckillAcceptContext(message, outboxMessage, reconcileLog, seckillVoucher);
    }

    public static class SeckillAcceptContext {
        private final SeckillOrderMessage message;
        private final MqOutboxMessage outboxMessage;
        private final VoucherReconcileLog reconcileLog;
        private final SeckillVoucher seckillVoucher;

        public SeckillAcceptContext(SeckillOrderMessage message, MqOutboxMessage outboxMessage,
                                    VoucherReconcileLog reconcileLog, SeckillVoucher seckillVoucher) {
            this.message = message;
            this.outboxMessage = outboxMessage;
            this.reconcileLog = reconcileLog;
            this.seckillVoucher = seckillVoucher;
        }

        public SeckillOrderMessage getMessage() {
            return message;
        }

        public MqOutboxMessage getOutboxMessage() {
            return outboxMessage;
        }

        public VoucherReconcileLog getReconcileLog() {
            return reconcileLog;
        }

        public SeckillVoucher getSeckillVoucher() {
            return seckillVoucher;
        }
    }
}
