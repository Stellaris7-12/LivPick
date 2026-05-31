package com.livepick.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.livepick.config.LivPickProperties;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.IOrderTimeoutService;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.SeckillReservationService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.utils.OrderStatusConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutServiceImpl implements IOrderTimeoutService {

    private final VoucherOrderMapper voucherOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;
    private final LivPickProperties livPickProperties;
    private final SeckillReservationService seckillReservationService;
    private final BenchmarkMetricsService benchmarkMetricsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeTimeoutOrder(Long orderId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }

        boolean closed = voucherOrderMapper.update(
                null,
                new UpdateWrapper<VoucherOrder>()
                        .eq("id", orderId)
                        .eq("status", OrderStatusConstants.UNPAID)
                        .set("status", OrderStatusConstants.CANCELLED)
                        .set("update_time", LocalDateTime.now())
        ) > 0;
        if (!closed) {
            return false;
        }

        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        seckillReservationService.rollbackReservationAfterTimeoutCancel(
                order.getVoucherId(),
                order.getUserId(),
                order.getId()
        );
        benchmarkMetricsService.incrementTimeoutClosed();
        return true;
    }

    @Override
    public void scanAndCloseTimeoutOrders() {
        LocalDateTime expireBefore = LocalDateTime.now().minusMinutes(livPickProperties.getOrder().getTimeoutMinutes());
        while (true) {
            List<VoucherOrder> timeoutOrders = voucherOrderMapper.selectList(
                    new QueryWrapper<VoucherOrder>()
                            .eq("status", OrderStatusConstants.UNPAID)
                            .lt("create_time", expireBefore)
                            .last("LIMIT " + livPickProperties.getOrder().getTimeoutScanBatchSize())
            );
            if (timeoutOrders.isEmpty()) {
                return;
            }
            timeoutOrders.forEach(order -> {
                try {
                    closeTimeoutOrder(order.getId());
                } catch (Exception e) {
                    log.error("close timeout order failed, orderId={}", order.getId(), e);
                }
            });
            if (timeoutOrders.size() < livPickProperties.getOrder().getTimeoutScanBatchSize()) {
                return;
            }
        }
    }
}
