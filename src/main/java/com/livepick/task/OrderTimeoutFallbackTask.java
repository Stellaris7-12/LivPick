package com.livepick.task;

import com.livepick.service.IOrderTimeoutService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderTimeoutFallbackTask {

    private final IOrderTimeoutService orderTimeoutService;
    private final BenchmarkRuntimeConfigService runtimeConfigService;

    @Scheduled(fixedDelayString = "${livpick.order.timeout-scan-interval-ms}")
    public void scanTimeoutOrders() {
        if (runtimeConfigService.isBenchmarkEnabled()
                && runtimeConfigService.getTimeoutMode() == BenchmarkRuntimeConfigService.TimeoutMode.FALLBACK_ONLY) {
            orderTimeoutService.scanAndCloseTimeoutOrders();
            return;
        }
        orderTimeoutService.scanAndCloseTimeoutOrders();
    }
}
