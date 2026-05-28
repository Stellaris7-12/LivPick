package com.livepick.utils;

import cn.hutool.json.JSONUtil;
import com.livepick.entity.Shop;
import com.livepick.entity.Voucher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheClient cacheClient;
    private Map<String, String> redisStore;

    @BeforeEach
    void setUp() {
        cacheClient = new CacheClient(stringRedisTemplate);
        redisStore = new ConcurrentHashMap<>();

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));

        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString());

        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String value = invocation.getArgument(1);
                    return redisStore.putIfAbsent(key, value) == null;
                });

        when(stringRedisTemplate.delete(anyString())).thenAnswer(invocation -> redisStore.remove(invocation.getArgument(0)) != null);
    }

    @Test
    void shouldCacheMissingShopAsNullWrapper() {
        Shop shop = cacheClient.queryWithLogicalExpire(
                "cache:shop:",
                "lock:shop:",
                404L,
                Shop.class,
                id -> null,
                30L,
                TimeUnit.MINUTES
        );

        assertNull(shop);

        RedisData redisData = JSONUtil.toBean(redisStore.get("cache:shop:404"), RedisData.class);
        assertFalse(redisData.getPresent());
        assertNotNull(redisData.getLogicalExpireTime());
        assertNull(redisData.getData());
    }

    @Test
    void shouldCacheShopDataOnMiss() {
        Shop dbShop = new Shop();
        dbShop.setId(1L);
        dbShop.setName("cache-shop");

        Shop shop = cacheClient.queryWithLogicalExpire(
                "cache:shop:",
                "lock:shop:",
                1L,
                Shop.class,
                id -> dbShop,
                30L,
                TimeUnit.MINUTES
        );

        assertEquals("cache-shop", shop.getName());

        RedisData redisData = JSONUtil.toBean(redisStore.get("cache:shop:1"), RedisData.class);
        assertTrue(redisData.getPresent());
        Shop cachedShop = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), Shop.class);
        assertEquals(1L, cachedShop.getId());
        assertEquals("cache-shop", cachedShop.getName());
    }

    @Test
    void shouldReturnStaleShopAndRebuildAsyncWhenExpired() throws Exception {
        Shop staleShop = new Shop();
        staleShop.setId(1L);
        staleShop.setName("stale-shop");
        putWrappedValue("cache:shop:1", true, staleShop, LocalDateTime.now().minusSeconds(5));

        Shop freshShop = new Shop();
        freshShop.setId(1L);
        freshShop.setName("fresh-shop");
        CountDownLatch rebuildLatch = new CountDownLatch(1);

        Shop returnedShop = cacheClient.queryWithLogicalExpire(
                "cache:shop:",
                "lock:shop:",
                1L,
                Shop.class,
                id -> {
                    rebuildLatch.countDown();
                    return freshShop;
                },
                30L,
                TimeUnit.MINUTES
        );

        assertEquals("stale-shop", returnedShop.getName());
        assertTrue(rebuildLatch.await(2, TimeUnit.SECONDS));

        waitForValue("cache:shop:1", "fresh-shop");
    }

    @Test
    void shouldCacheEmptyVoucherListForShopWithoutVouchers() {
        List<Voucher> vouchers = cacheClient.queryListWithLogicalExpire(
                "cache:voucher:list:",
                "lock:voucher:list:",
                99L,
                Voucher.class,
                id -> Collections.emptyList(),
                30L,
                TimeUnit.MINUTES
        );

        assertTrue(vouchers.isEmpty());

        RedisData redisData = JSONUtil.toBean(redisStore.get("cache:voucher:list:99"), RedisData.class);
        assertTrue(redisData.getPresent());
        assertTrue(JSONUtil.toList(JSONUtil.parseArray(redisData.getData()), Voucher.class).isEmpty());
    }

    @Test
    void shouldReturnStaleVoucherListAndRebuildAsyncWhenExpired() throws Exception {
        Voucher staleVoucher = new Voucher();
        staleVoucher.setId(1L);
        staleVoucher.setTitle("stale-voucher");
        putWrappedValue("cache:voucher:list:1", true, Collections.singletonList(staleVoucher), LocalDateTime.now().minusSeconds(5));

        Voucher freshVoucher = new Voucher();
        freshVoucher.setId(2L);
        freshVoucher.setTitle("fresh-voucher");
        CountDownLatch rebuildLatch = new CountDownLatch(1);
        AtomicReference<List<Voucher>> rebuildResult = new AtomicReference<>();

        List<Voucher> returned = cacheClient.queryListWithLogicalExpire(
                "cache:voucher:list:",
                "lock:voucher:list:",
                1L,
                Voucher.class,
                id -> {
                    List<Voucher> freshList = Arrays.asList(freshVoucher);
                    rebuildResult.set(freshList);
                    rebuildLatch.countDown();
                    return freshList;
                },
                30L,
                TimeUnit.MINUTES
        );

        assertEquals(1, returned.size());
        assertEquals("stale-voucher", returned.get(0).getTitle());
        assertTrue(rebuildLatch.await(2, TimeUnit.SECONDS));

        waitForValue("cache:voucher:list:1", "fresh-voucher");
        assertEquals("fresh-voucher", rebuildResult.get().get(0).getTitle());
    }

    private void putWrappedValue(String key, boolean present, Object value, LocalDateTime logicalExpireTime) {
        RedisData redisData = new RedisData();
        redisData.setPresent(present);
        redisData.setData(value);
        redisData.setLogicalExpireTime(logicalExpireTime);
        redisStore.put(key, JSONUtil.toJsonStr(redisData));
    }

    private void waitForValue(String key, String expectedText) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            String json = redisStore.get(key);
            if (json != null && json.contains(expectedText)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for redis value to contain: " + expectedText);
    }
}
