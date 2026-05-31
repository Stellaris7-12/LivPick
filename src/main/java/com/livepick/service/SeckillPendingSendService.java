package com.livepick.service;

import cn.hutool.json.JSONUtil;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.PendingSeckillOrderMessage;
import com.livepick.mq.message.SeckillOrderMessage;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.livepick.utils.RedisConstants.SECKILL_PENDING_SEND_INDEX_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_PENDING_SEND_KEY;

@Component
@RequiredArgsConstructor
public class SeckillPendingSendService {

    private final StringRedisTemplate stringRedisTemplate;
    private final LivPickProperties livPickProperties;
    private final BenchmarkMetricsService benchmarkMetricsService;

    public void register(SeckillOrderMessage message) {
        PendingSeckillOrderMessage pendingMessage = new PendingSeckillOrderMessage();
        pendingMessage.setOrderId(message.getOrderId());
        pendingMessage.setUserId(message.getUserId());
        pendingMessage.setVoucherId(message.getVoucherId());
        pendingMessage.setCreateTime(message.getCreateTime());
        pendingMessage.setRetryCount(0);
        pendingMessage.setNextRetryAt(System.currentTimeMillis() + livPickProperties.getSeckill().getPendingSendRetryDelayMs());
        save(pendingMessage);
        benchmarkMetricsService.incrementPendingRegistered();
    }

    public void save(PendingSeckillOrderMessage pendingMessage) {
        String pendingKey = buildPendingKey(pendingMessage.getOrderId());
        long ttlMinutes = livPickProperties.getSeckill().getPendingSendTtlMinutes();
        stringRedisTemplate.opsForValue().set(pendingKey, JSONUtil.toJsonStr(pendingMessage), ttlMinutes, TimeUnit.MINUTES);
        stringRedisTemplate.opsForZSet().add(
                SECKILL_PENDING_SEND_INDEX_KEY,
                pendingMessage.getOrderId().toString(),
                pendingMessage.getNextRetryAt()
        );
    }

    public void clear(Long orderId) {
        stringRedisTemplate.delete(buildPendingKey(orderId));
        stringRedisTemplate.opsForZSet().remove(SECKILL_PENDING_SEND_INDEX_KEY, orderId.toString());
    }

    public List<PendingSeckillOrderMessage> pollDueMessages() {
        Set<String> dueOrderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                SECKILL_PENDING_SEND_INDEX_KEY,
                0,
                System.currentTimeMillis(),
                0,
                livPickProperties.getSeckill().getPendingSendBatchSize()
        );
        List<PendingSeckillOrderMessage> messages = new ArrayList<>();
        if (dueOrderIds == null || dueOrderIds.isEmpty()) {
            return messages;
        }
        for (String orderId : dueOrderIds) {
            String pendingJson = stringRedisTemplate.opsForValue().get(buildPendingKey(Long.valueOf(orderId)));
            if (pendingJson == null) {
                stringRedisTemplate.opsForZSet().remove(SECKILL_PENDING_SEND_INDEX_KEY, orderId);
                continue;
            }
            messages.add(JSONUtil.toBean(pendingJson, PendingSeckillOrderMessage.class));
        }
        return messages;
    }

    private String buildPendingKey(Long orderId) {
        return SECKILL_PENDING_SEND_KEY + orderId;
    }
}
