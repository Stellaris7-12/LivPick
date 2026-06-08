package com.livepick.controller;

import com.livepick.config.LivPickProperties;
import com.livepick.dto.Result;
import com.livepick.service.IShopService;
import com.livepick.service.MqOutboxService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.livepick.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_REORDER_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_TRACE_KEY;

@RestController
@RequestMapping("/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final LivPickProperties livPickProperties;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final BenchmarkRuntimeConfigService benchmarkRuntimeConfigService;
    private final StringRedisTemplate stringRedisTemplate;
    private final IShopService shopService;
    private final MqOutboxService mqOutboxService;

    @GetMapping("/metrics")
    public Result metrics() {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        return Result.ok(benchmarkMetricsService.snapshot());
    }

    @PostMapping("/admin/metrics/reset")
    public Result resetMetrics() {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        benchmarkMetricsService.reset();
        return Result.ok();
    }

    @PostMapping("/admin/seckill/reset")
    public Result resetSeckillState() {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        long backlog = mqOutboxService.countInFlight();
        Set<String> traceKeys = stringRedisTemplate.keys(SECKILL_TRACE_KEY + "*");
        if (traceKeys != null && !traceKeys.isEmpty()) {
            stringRedisTemplate.delete(traceKeys);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outboxBacklog", backlog);
        data.put("traceKeysCleared", traceKeys == null ? 0 : traceKeys.size());
        return Result.ok(data);
    }

    @GetMapping("/admin/mq/drain-status")
    public Result drainStatus() {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        long backlog = mqOutboxService.countInFlight();
        benchmarkMetricsService.updateMqBacklog(backlog);
        if (backlog == 0L) {
            benchmarkMetricsService.markMqDrained();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outboxBacklog", backlog);
        data.put("drained", backlog == 0L);
        data.put("consumerPaused", benchmarkRuntimeConfigService.isConsumerPaused());
        return Result.ok(data);
    }

    @GetMapping("/admin/config")
    public Result config() {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        return Result.ok(benchmarkRuntimeConfigService.snapshot());
    }

    @PostMapping("/admin/config/cache-penetration")
    public Result updateCachePenetrationConfig(@RequestBody Map<String, Object> request) {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        benchmarkRuntimeConfigService.setCachePenetrationMode(String.valueOf(request.get("mode")));
        return Result.ok(benchmarkRuntimeConfigService.snapshot());
    }

    @PostMapping("/admin/config/timeout-mode")
    public Result updateTimeoutMode(@RequestBody Map<String, Object> request) {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        benchmarkRuntimeConfigService.setTimeoutMode(String.valueOf(request.get("mode")));
        Object secondsOverride = request.get("orderTimeoutSeconds");
        if (secondsOverride != null) {
            benchmarkRuntimeConfigService.setOrderTimeoutSecondsOverride(Long.valueOf(String.valueOf(secondsOverride)));
        }
        return Result.ok(benchmarkRuntimeConfigService.snapshot());
    }

    @PostMapping("/admin/config/consumer-pause")
    public Result updateConsumerPause(@RequestBody Map<String, Object> request) {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        boolean paused = Boolean.parseBoolean(String.valueOf(request.getOrDefault("paused", "false")));
        benchmarkRuntimeConfigService.setConsumerPaused(paused);
        return Result.ok(benchmarkRuntimeConfigService.snapshot());
    }

    @PostMapping("/admin/cache/shop/{id}/warm")
    public Result warmShopCache(@PathVariable("id") Long shopId) {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        return shopService.queryByIdWithMode(shopId, benchmarkRuntimeConfigService.isCachePenetrationProtectionEnabled());
    }

    @PostMapping("/admin/cache/shop/{id}/expire")
    public Result expireShopCache(@PathVariable("id") Long shopId) {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }

    @PostMapping("/admin/seckill/reset-redis")
    public Result resetSeckillRedisState(@RequestBody Map<String, Object> request) {
        if (!benchmarkRuntimeConfigService.isBenchmarkEnabled()) {
            return Result.fail("benchmark disabled");
        }
        Long voucherId = Long.valueOf(String.valueOf(request.get("voucherId")));
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
        stringRedisTemplate.delete(SECKILL_TRACE_KEY + voucherId);
        Set<String> reorderKeys = stringRedisTemplate.keys(SECKILL_REORDER_KEY + voucherId + ":*");
        if (reorderKeys != null && !reorderKeys.isEmpty()) {
            stringRedisTemplate.delete(reorderKeys);
        }
        return Result.ok();
    }
}
