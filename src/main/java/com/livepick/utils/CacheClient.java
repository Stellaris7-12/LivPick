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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    // 创建固定大小线程池用于异步缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        setWrappedValue(key, true, value, time, unit);
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

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return rebuildObjectCache(key, id, type, dbFallback, time, unit);
        }
        if (isLegacyPlainValue(json)) {
            R legacyValue = JSONUtil.toBean(json, type);
            setWithLogicalExpire(key, legacyValue, time, unit);
            return legacyValue;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (Boolean.FALSE.equals(redisData.getPresent())) {
            return null;
        }

        R value = deserializeObject(redisData.getData(), type);
        LocalDateTime expireTime = resolveExpireTime(redisData);
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return value;
        }

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
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return rebuildListCache(key, id, dbFallback, time, unit);
        }

        if (isLegacyPlainValue(json)) {
            List<R> legacyList = JSONUtil.toList(json, type);
            setWithLogicalExpire(key, legacyList, time, unit);
            return legacyList;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (Boolean.FALSE.equals(redisData.getPresent())) {
            return Collections.emptyList();
        }

        List<R> values = deserializeList(redisData.getData(), type);
        LocalDateTime expireTime = resolveExpireTime(redisData);
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return values;
        }

        rebuildListCacheAsync(key, lockKeyPrefix + id, id, dbFallback, time, unit);
        return values;
    }

    private <R, ID> R rebuildObjectCache(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
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
            return;
        }
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                R value = dbFallback.apply(id);
                if (value == null) {
                    setNullWithLogicalExpire(key, time, unit);
                    return;
                }
                setWithLogicalExpire(key, value, time, unit);
            } catch (Exception e) {
                log.error("缓存重建失败", e);
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
            return;
        }
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                List<R> values = dbFallback.apply(id);
                if (values == null) {
                    values = Collections.emptyList();
                }
                setWithLogicalExpire(key, values, time, unit);
            } catch (Exception e) {
                log.error("缓存重建失败", e);
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
