package com.livepick.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
@RequiredArgsConstructor
public class SeckillReservationService {

    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_STOCK_ROLLBACK_SCRIPT;

    static {
        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);

        SECKILL_STOCK_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_STOCK_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_stock_rollback.lua"));
        SECKILL_STOCK_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public void rollbackReservationBeforeOrderCreated(Long voucherId, Long userId) {
        stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
    }

    public void rollbackStockAfterTimeoutCancel(Long voucherId) {
        stringRedisTemplate.execute(
                SECKILL_STOCK_ROLLBACK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString()
        );
    }
}
