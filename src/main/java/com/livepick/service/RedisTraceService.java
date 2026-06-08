package com.livepick.service;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.livepick.config.LivPickProperties;
import com.livepick.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisTraceService {

    private final StringRedisTemplate stringRedisTemplate;
    private final LivPickProperties livPickProperties;

    public void saveDeductTrace(Long voucherId, Long traceId, Long orderId, Long userId,
                                Integer beforeQty, Integer changeQty, Integer afterQty,
                                LocalDateTime expireAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("traceId", traceId);
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        payload.put("voucherId", voucherId);
        payload.put("logType", "DEDUCT");
        payload.put("beforeQty", beforeQty);
        payload.put("changeQty", changeQty);
        payload.put("afterQty", afterQty);
        payload.put("ts", System.currentTimeMillis());
        String key = traceKey(voucherId);
        stringRedisTemplate.opsForHash().put(key, traceId.toString(), JSONUtil.toJsonStr(payload));
        long ttlSeconds = resolveTraceTtlSeconds(expireAt);
        Long current = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (current == null || current <= 0) {
            stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    public Map<String, String> readAll(Long voucherId) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(traceKey(voucherId));
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    public void deleteTrace(Long voucherId, Long traceId) {
        stringRedisTemplate.opsForHash().delete(traceKey(voucherId), traceId.toString());
    }

    public void restoreTrace(Long voucherId, Long traceId, String json, LocalDateTime expireAt) {
        String key = traceKey(voucherId);
        stringRedisTemplate.opsForHash().put(key, traceId.toString(), json);
        Long current = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (current == null || current <= 0) {
            stringRedisTemplate.expire(key, resolveTraceTtlSeconds(expireAt), TimeUnit.SECONDS);
        }
    }

    public long resolveTraceTtlSeconds(LocalDateTime expireAt) {
        if (expireAt == null) {
            return livPickProperties.getReconcile().getTraceFallbackTtlSeconds();
        }
        long seconds = Duration.between(LocalDateTime.now(), expireAt.plusDays(1)).getSeconds();
        return Math.max(seconds, livPickProperties.getReconcile().getTraceFallbackTtlSeconds());
    }

    public String traceKey(Long voucherId) {
        return RedisConstants.SECKILL_TRACE_KEY + voucherId;
    }

    public TraceSnapshot parse(String json) {
        JSONObject object = JSONUtil.parseObj(json);
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setTraceId(object.getLong("traceId"));
        snapshot.setOrderId(object.getLong("orderId"));
        snapshot.setUserId(object.getLong("userId"));
        snapshot.setVoucherId(object.getLong("voucherId"));
        snapshot.setLogType(object.getStr("logType"));
        snapshot.setBeforeQty(object.getInt("beforeQty"));
        snapshot.setChangeQty(object.getInt("changeQty"));
        snapshot.setAfterQty(object.getInt("afterQty"));
        snapshot.setTs(object.getLong("ts"));
        return snapshot;
    }

    public long nowEpochMillis() {
        return LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @lombok.Data
    public static class TraceSnapshot {
        private Long traceId;
        private Long orderId;
        private Long userId;
        private Long voucherId;
        private String logType;
        private Integer beforeQty;
        private Integer changeQty;
        private Integer afterQty;
        private Long ts;
    }
}
