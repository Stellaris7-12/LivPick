package com.livepick.task;

import com.livepick.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderTimeoutFallbackTask {

    private final IVoucherOrderService voucherOrderService;

    @Scheduled(fixedDelayString = "${livpick.order.timeout-scan-interval-ms}")
    public void scanTimeoutOrders() {
        voucherOrderService.scanAndCloseTimeoutOrders();
    }
}
