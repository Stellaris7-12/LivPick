package com.livepick.controller;

import com.livepick.config.LivPickProperties;
import com.livepick.dto.Result;
import com.livepick.service.SeckillPendingSendService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.livepick.utils.RedisConstants.SECKILL_PENDING_SEND_INDEX_KEY;

@RestController
@RequestMapping("/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final LivPickProperties livPickProperties;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillPendingSendService seckillPendingSendService;

    @GetMapping("/metrics")
    public Result metrics() {
        if (!livPickProperties.getBenchmark().isEnabled()) {
            return Result.fail("benchmark disabled");
        }
        return Result.ok(benchmarkMetricsService.snapshot());
    }

    @PostMapping("/admin/metrics/reset")
    public Result resetMetrics() {
        if (!livPickProperties.getBenchmark().isEnabled()) {
            return Result.fail("benchmark disabled");
        }
        benchmarkMetricsService.reset();
        return Result.ok();
    }

    @PostMapping("/admin/seckill/reset")
    public Result resetSeckillState() {
        if (!livPickProperties.getBenchmark().isEnabled()) {
            return Result.fail("benchmark disabled");
        }
        Set<String> pendingIds = stringRedisTemplate.opsForZSet().range(SECKILL_PENDING_SEND_INDEX_KEY, 0, -1);
        if (pendingIds != null) {
            for (String pendingId : pendingIds) {
                seckillPendingSendService.clear(Long.valueOf(pendingId));
            }
        }
        return Result.ok();
    }

    @GetMapping("/admin/mq/drain-status")
    public Result drainStatus() {
        if (!livPickProperties.getBenchmark().isEnabled()) {
            return Result.fail("benchmark disabled");
        }
        Long pendingCount = stringRedisTemplate.opsForZSet().zCard(SECKILL_PENDING_SEND_INDEX_KEY);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingSendBacklog", pendingCount == null ? 0L : pendingCount);
        data.put("drained", pendingCount == null || pendingCount == 0L);
        return Result.ok(data);
    }
}
