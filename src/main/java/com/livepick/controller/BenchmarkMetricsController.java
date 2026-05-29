package com.livepick.controller;

import com.livepick.dto.Result;
import com.livepick.service.IShopService;
import com.livepick.utils.CacheClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.livepick.utils.RedisConstants.CACHE_SHOP_KEY;

@RestController
@RequestMapping("/benchmark")
public class BenchmarkMetricsController {

    @Value("${app.benchmark.enabled:false}")
    private boolean benchmarkEnabled;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/metrics")
    public Result metrics() {
        Result guard = checkBenchmarkEnabled();
        if (guard != null) {
            return guard;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cache", cacheClient.snapshotMetrics());
        Map<String, Object> redis = new LinkedHashMap<>();
        Set<String> shopKeys = stringRedisTemplate.keys(CACHE_SHOP_KEY + "*");
        redis.put("shopCacheKeyCount", shopKeys == null ? 0 : shopKeys.size());
        payload.put("redis", redis);
        return Result.ok(payload);
    }

    @PostMapping("/admin/metrics/reset")
    public Result resetMetrics() {
        Result guard = checkBenchmarkEnabled();
        if (guard != null) {
            return guard;
        }
        cacheClient.resetMetrics();
        return Result.ok();
    }

    @PostMapping("/admin/cache/shop/{id}/warm")
    public Result warmShopCache(@PathVariable("id") Long shopId) {
        Result guard = checkBenchmarkEnabled();
        if (guard != null) {
            return guard;
        }
        return shopService.queryById(shopId);
    }

    @PostMapping("/admin/cache/shop/{id}/expire")
    public Result expireShopCache(@PathVariable("id") Long shopId) {
        Result guard = checkBenchmarkEnabled();
        if (guard != null) {
            return guard;
        }
        boolean updated = cacheClient.expireLogicalNow(CACHE_SHOP_KEY + shopId);
        if (!updated) {
            return Result.fail("shop cache not found or not wrapped");
        }
        return Result.ok();
    }

    private Result checkBenchmarkEnabled() {
        if (!benchmarkEnabled) {
            return Result.fail("benchmark mode is disabled");
        }
        return null;
    }
}
