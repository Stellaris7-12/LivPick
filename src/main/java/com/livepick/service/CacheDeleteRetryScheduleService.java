package com.livepick.service;

import cn.hutool.json.JSONUtil;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.livepick.utils.RedisConstants.CACHE_DELETE_RETRY_INDEX_KEY;
import static com.livepick.utils.RedisConstants.CACHE_DELETE_RETRY_KEY;

@Component
@RequiredArgsConstructor
public class CacheDeleteRetryScheduleService {

    private final StringRedisTemplate stringRedisTemplate;
    private final LivPickProperties livPickProperties;

    public void schedule(CacheDeleteRetryMessage message) {
        long nextRetryAt = System.currentTimeMillis() + calculateDelayMs(message.getRetryCount());
        message.setNextRetryAt(nextRetryAt);
        String scheduleKey = buildScheduleKey(message.getCacheKey());
        stringRedisTemplate.opsForValue().set(
                scheduleKey,
                JSONUtil.toJsonStr(message),
                livPickProperties.getCache().getDeleteRetryMessageTtlMinutes(),
                TimeUnit.MINUTES
        );
        stringRedisTemplate.opsForZSet().add(CACHE_DELETE_RETRY_INDEX_KEY, message.getCacheKey(), nextRetryAt);
    }

    public void clear(String cacheKey) {
        stringRedisTemplate.delete(buildScheduleKey(cacheKey));
        stringRedisTemplate.opsForZSet().remove(CACHE_DELETE_RETRY_INDEX_KEY, cacheKey);
    }

    public List<CacheDeleteRetryMessage> pollDueMessages() {
        Set<String> cacheKeys = stringRedisTemplate.opsForZSet().rangeByScore(
                CACHE_DELETE_RETRY_INDEX_KEY,
                0,
                System.currentTimeMillis(),
                0,
                livPickProperties.getCache().getDeleteRetryBatchSize()
        );
        List<CacheDeleteRetryMessage> messages = new ArrayList<>();
        if (cacheKeys == null || cacheKeys.isEmpty()) {
            return messages;
        }
        for (String cacheKey : cacheKeys) {
            String messageJson = stringRedisTemplate.opsForValue().get(buildScheduleKey(cacheKey));
            if (messageJson == null) {
                stringRedisTemplate.opsForZSet().remove(CACHE_DELETE_RETRY_INDEX_KEY, cacheKey);
                continue;
            }
            messages.add(JSONUtil.toBean(messageJson, CacheDeleteRetryMessage.class));
        }
        return messages;
    }

    private long calculateDelayMs(int retryCount) {
        long initialDelayMs = livPickProperties.getCache().getDeleteRetryInitialDelayMs();
        long maxDelayMs = livPickProperties.getCache().getDeleteRetryMaxDelayMs();
        long delayMs = initialDelayMs;
        for (int i = 1; i < retryCount; i++) {
            if (delayMs >= maxDelayMs) {
                return maxDelayMs;
            }
            delayMs = Math.min(delayMs * 2, maxDelayMs);
        }
        return delayMs;
    }

    private String buildScheduleKey(String cacheKey) {
        return CACHE_DELETE_RETRY_KEY + cacheKey;
    }
}
