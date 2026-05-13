package com.livepick.task;

import com.livepick.service.IOrderTimeoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderTimeoutFallbackTask {

    private final IOrderTimeoutService orderTimeoutService;

    @Scheduled(fixedDelayString = "${livpick.order.timeout-scan-interval-ms}")
    public void scanTimeoutOrders() {
        orderTimeoutService.scanAndCloseTimeoutOrders();
    }
}
