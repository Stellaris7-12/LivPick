package com.livepick.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong cacheHit = new AtomicLong();
    private final AtomicLong nullHit = new AtomicLong();
    private final AtomicLong cacheMiss = new AtomicLong();
    private final AtomicLong dbFallback = new AtomicLong();
    private final AtomicLong staleHit = new AtomicLong();
    private final AtomicLong rebuildScheduled = new AtomicLong();
    private final AtomicLong rebuildSuccess = new AtomicLong();
    private final AtomicLong rebuildFailure = new AtomicLong();
    private final AtomicLong rebuildLockHit = new AtomicLong();
    private final AtomicLong rebuildLockMiss = new AtomicLong();

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        setWrappedValue(key, true, value, time, unit);
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        totalRequests.incrementAndGet();
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            cacheMiss.incrementAndGet();
            return rebuildObjectCache(key, id, dbFallback, time, unit);
        }
        if (isLegacyPlainValue(json)) {
            R legacyValue = JSONUtil.toBean(json, type);
            setWithLogicalExpire(key, legacyValue, time, unit);
            cacheHit.incrementAndGet();
            return legacyValue;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (Boolean.FALSE.equals(redisData.getPresent())) {
            nullHit.incrementAndGet();
            return null;
        }

        R value = deserializeObject(redisData.getData(), type);
        LocalDateTime expireTime = resolveExpireTime(redisData);
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            cacheHit.incrementAndGet();
            return value;
        }

        staleHit.incrementAndGet();
        rebuildObjectCacheAsync(key, lockKeyPrefix + id, id, dbFallback, time, unit);
        return value;
    }

    public <R, ID> List<R> queryListWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, List<R>> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        totalRequests.incrementAndGet();
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            cacheMiss.incrementAndGet();
            return rebuildListCache(key, id, dbFallback, time, unit);
        }
        if (isLegacyPlainValue(json)) {
            List<R> legacyList = JSONUtil.toList(json, type);
            setWithLogicalExpire(key, legacyList, time, unit);
            cacheHit.incrementAndGet();
            return legacyList;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (Boolean.FALSE.equals(redisData.getPresent())) {
            nullHit.incrementAndGet();
            return Collections.emptyList();
        }

        List<R> values = deserializeList(redisData.getData(), type);
        LocalDateTime expireTime = resolveExpireTime(redisData);
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            cacheHit.incrementAndGet();
            return values;
        }

        staleHit.incrementAndGet();
        rebuildListCacheAsync(key, lockKeyPrefix + id, id, dbFallback, time, unit);
        return values;
    }

    public Map<String, Long> snapshotMetrics() {
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("totalRequests", totalRequests.get());
        metrics.put("cacheHit", cacheHit.get());
        metrics.put("nullHit", nullHit.get());
        metrics.put("cacheMiss", cacheMiss.get());
        metrics.put("dbFallback", dbFallback.get());
        metrics.put("staleHit", staleHit.get());
        metrics.put("rebuildScheduled", rebuildScheduled.get());
        metrics.put("rebuildSuccess", rebuildSuccess.get());
        metrics.put("rebuildFailure", rebuildFailure.get());
        metrics.put("rebuildLockHit", rebuildLockHit.get());
        metrics.put("rebuildLockMiss", rebuildLockMiss.get());
        return metrics;
    }

    public void resetMetrics() {
        totalRequests.set(0);
        cacheHit.set(0);
        nullHit.set(0);
        cacheMiss.set(0);
        dbFallback.set(0);
        staleHit.set(0);
        rebuildScheduled.set(0);
        rebuildSuccess.set(0);
        rebuildFailure.set(0);
        rebuildLockHit.set(0);
        rebuildLockMiss.set(0);
    }

    public boolean expireLogicalNow(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json) || isLegacyPlainValue(json)) {
            return false;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        redisData.setLogicalExpireTime(LocalDateTime.now().minusSeconds(1));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        return true;
    }

    private void setNullWithLogicalExpire(String key, Long time, TimeUnit unit) {
        setWrappedValue(key, false, null, time, unit);
    }

    private void setWrappedValue(String key, boolean present, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setPresent(present);
        redisData.setData(value);
        redisData.setLogicalExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    private <R, ID> R rebuildObjectCache(
            String key,
            ID id,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        this.dbFallback.incrementAndGet();
        R value = dbFallback.apply(id);
        if (value == null) {
            setNullWithLogicalExpire(key, time, unit);
            return null;
        }
        setWithLogicalExpire(key, value, time, unit);
        return value;
    }

    private <R, ID> List<R> rebuildListCache(
            String key,
            ID id,
            Function<ID, List<R>> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        this.dbFallback.incrementAndGet();
        List<R> values = dbFallback.apply(id);
        if (values == null) {
            values = Collections.emptyList();
        }
        setWithLogicalExpire(key, values, time, unit);
        return values;
    }

    private <R, ID> void rebuildObjectCacheAsync(
            String key,
            String lockKey,
            ID id,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        if (!tryLock(lockKey)) {
            rebuildLockMiss.incrementAndGet();
            return;
        }
        rebuildLockHit.incrementAndGet();
        rebuildScheduled.incrementAndGet();
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                this.dbFallback.incrementAndGet();
                R value = dbFallback.apply(id);
                if (value == null) {
                    setNullWithLogicalExpire(key, time, unit);
                } else {
                    setWithLogicalExpire(key, value, time, unit);
                }
                rebuildSuccess.incrementAndGet();
            } catch (Exception e) {
                rebuildFailure.incrementAndGet();
                log.error("cache rebuild failed", e);
            } finally {
                unlock(lockKey);
            }
        });
    }

    private <R, ID> void rebuildListCacheAsync(
            String key,
            String lockKey,
            ID id,
            Function<ID, List<R>> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        if (!tryLock(lockKey)) {
            rebuildLockMiss.incrementAndGet();
            return;
        }
        rebuildLockHit.incrementAndGet();
        rebuildScheduled.incrementAndGet();
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                this.dbFallback.incrementAndGet();
                List<R> values = dbFallback.apply(id);
                if (values == null) {
                    values = Collections.emptyList();
                }
                setWithLogicalExpire(key, values, time, unit);
                rebuildSuccess.incrementAndGet();
            } catch (Exception e) {
                rebuildFailure.incrementAndGet();
                log.error("cache rebuild failed", e);
            } finally {
                unlock(lockKey);
            }
        });
    }

    private <R> R deserializeObject(Object data, Class<R> type) {
        if (data == null) {
            return null;
        }
        if (type.isInstance(data)) {
            return type.cast(data);
        }
        return JSONUtil.toBean((JSONObject) JSONUtil.parseObj(data), type);
    }

    private <R> List<R> deserializeList(Object data, Class<R> type) {
        if (data == null) {
            return Collections.emptyList();
        }
        if (data instanceof JSONArray) {
            return JSONUtil.toList((JSONArray) data, type);
        }
        return JSONUtil.toList(JSONUtil.parseArray(data), type);
    }

    private LocalDateTime resolveExpireTime(RedisData redisData) {
        if (redisData.getLogicalExpireTime() != null) {
            return redisData.getLogicalExpireTime();
        }
        return redisData.getExpireTime();
    }

    private boolean isLegacyPlainValue(String json) {
        return !json.contains("\"logicalExpireTime\"")
                && !json.contains("\"expireTime\"")
                && !json.contains("\"present\"");
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
