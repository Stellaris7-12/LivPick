package com.livepick.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.livepick.entity.MqOutboxMessage;
import com.livepick.enums.OutboxStatus;
import com.livepick.mapper.MqOutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MqOutboxService {

    private final MqOutboxMessageMapper mqOutboxMessageMapper;

    public void save(MqOutboxMessage message) {
        mqOutboxMessageMapper.insert(message);
    }

    public List<MqOutboxMessage> pollDispatchable(int limit) {
        return mqOutboxMessageMapper.selectList(new QueryWrapper<MqOutboxMessage>()
                .and(wrapper -> wrapper
                        .eq("status", OutboxStatus.PENDING.name())
                        .or()
                        .eq("status", OutboxStatus.FAILED.name()))
                .le("next_retry_at", LocalDateTime.now())
                .orderByAsc("created_time")
                .last("LIMIT " + limit));
    }

    public boolean markSending(Long id) {
        MqOutboxMessage current = mqOutboxMessageMapper.selectById(id);
        if (current == null) {
            return false;
        }
        if (!(OutboxStatus.PENDING.name().equals(current.getStatus())
                || OutboxStatus.FAILED.name().equals(current.getStatus()))) {
            return false;
        }
        MqOutboxMessage update = new MqOutboxMessage()
                .setId(id)
                .setStatus(OutboxStatus.SENDING.name())
                .setUpdateTime(LocalDateTime.now());
        return mqOutboxMessageMapper.updateById(update) > 0;
    }

    public void markSent(Long id) {
        mqOutboxMessageMapper.updateById(new MqOutboxMessage()
                .setId(id)
                .setStatus(OutboxStatus.SENT.name())
                .setUpdateTime(LocalDateTime.now())
                .setLastError(null));
    }

    public void markAckedByMessageId(String messageId) {
        MqOutboxMessage existing = mqOutboxMessageMapper.selectOne(new QueryWrapper<MqOutboxMessage>()
                .eq("message_id", messageId)
                .last("LIMIT 1"));
        if (existing == null) {
            return;
        }
        mqOutboxMessageMapper.updateById(new MqOutboxMessage()
                .setId(existing.getId())
                .setStatus(OutboxStatus.ACKED.name())
                .setUpdateTime(LocalDateTime.now())
                .setLastError(null));
    }

    public void markFailedOrDlq(MqOutboxMessage message, int maxAttempts, long nextBackoffMs, String error) {
        int nextRetryCount = message.getRetryCount() == null ? 1 : message.getRetryCount() + 1;
        MqOutboxMessage update = new MqOutboxMessage()
                .setId(message.getId())
                .setRetryCount(nextRetryCount)
                .setLastError(error)
                .setUpdateTime(LocalDateTime.now());
        if (nextRetryCount > maxAttempts) {
            update.setStatus(OutboxStatus.DLQ.name());
        } else {
            update.setStatus(OutboxStatus.FAILED.name());
            update.setNextRetryAt(LocalDateTime.now().plusNanos(nextBackoffMs * 1_000_000L));
        }
        mqOutboxMessageMapper.updateById(update);
    }

    public MqOutboxMessage findByMessageId(String messageId) {
        return mqOutboxMessageMapper.selectOne(new QueryWrapper<MqOutboxMessage>()
                .eq("message_id", messageId)
                .last("LIMIT 1"));
    }

    public List<MqOutboxMessage> findByStatus(String status, int limit) {
        return mqOutboxMessageMapper.selectList(new QueryWrapper<MqOutboxMessage>()
                .eq("status", status)
                .orderByAsc("created_time")
                .last("LIMIT " + limit));
    }

    public long countInFlight() {
        Integer count = mqOutboxMessageMapper.selectCount(new QueryWrapper<MqOutboxMessage>()
                .ne("status", OutboxStatus.ACKED.name())
                .ne("status", OutboxStatus.DLQ.name()));
        return count == null ? 0L : count;
    }
}
