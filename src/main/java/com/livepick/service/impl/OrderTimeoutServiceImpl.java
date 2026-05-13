package com.livepick.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.livepick.config.LivPickProperties;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.IOrderTimeoutService;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.utils.OrderStatusConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.livepick.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutServiceImpl implements IOrderTimeoutService {

    private final VoucherOrderMapper voucherOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;
    private final LivPickProperties livPickProperties;

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
        restoreRedisReservation(order.getVoucherId(), order.getUserId());
        return true;
    }

    @Override
    public void scanAndCloseTimeoutOrders() {
        LocalDateTime expireBefore = LocalDateTime.now().minusMinutes(livPickProperties.getOrder().getTimeoutMinutes());
        List<VoucherOrder> timeoutOrders = voucherOrderMapper.selectList(
                new QueryWrapper<VoucherOrder>()
                        .eq("status", OrderStatusConstants.UNPAID)
                        .lt("create_time", expireBefore)
                        .last("LIMIT 100")
        );
        timeoutOrders.forEach(order -> {
            try {
                closeTimeoutOrder(order.getId());
            } catch (Exception e) {
                log.error("close timeout order failed, orderId={}", order.getId(), e);
            }
        });
    }

    private void restoreRedisReservation(Long voucherId, Long userId) {
        stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + voucherId, userId.toString());
    }
}
