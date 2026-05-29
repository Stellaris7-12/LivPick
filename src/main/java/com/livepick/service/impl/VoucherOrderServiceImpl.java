package com.livepick.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.livepick.dto.Result;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.IVoucherOrderService;
import com.livepick.utils.RedisIdWorker;
import com.livepick.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final int SECKILL_OK = 0;
    private static final int SECKILL_NO_STOCK = 1;
    private static final int SECKILL_DUPLICATE = 2;
    private static final int SECKILL_UNKNOWN = 3;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long scriptResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int preCheckResult = scriptResult == null ? SECKILL_UNKNOWN : scriptResult.intValue();
        if (preCheckResult != SECKILL_OK) {
            return Result.fail(mapErrorMessage(preCheckResult));
        }

        try {
            int createResult = createVoucherOrder(orderId, userId, voucherId);
            if (createResult != SECKILL_OK) {
                rollbackRedisReservation(voucherId, userId);
                return Result.fail(mapErrorMessage(createResult));
            }
            return Result.ok(orderId);
        } catch (Exception e) {
            rollbackRedisReservation(voucherId, userId);
            log.error("create voucher order failed", e);
            return Result.fail(mapErrorMessage(SECKILL_UNKNOWN));
        }
    }

    private int createVoucherOrder(Long orderId, Long userId, Long voucherId) {
        Integer result = transactionTemplate.execute(status -> doCreateVoucherOrder(status, orderId, userId, voucherId));
        return result == null ? SECKILL_UNKNOWN : result;
    }

    private int doCreateVoucherOrder(TransactionStatus status, Long orderId, Long userId, Long voucherId) {
        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            status.setRollbackOnly();
            return SECKILL_NO_STOCK;
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        try {
            boolean saved = save(voucherOrder);
            if (!saved) {
                status.setRollbackOnly();
                return SECKILL_UNKNOWN;
            }
            return SECKILL_OK;
        } catch (DataIntegrityViolationException e) {
            status.setRollbackOnly();
            return SECKILL_DUPLICATE;
        }
    }

    private void rollbackRedisReservation(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.execute(
                    SECKILL_ROLLBACK_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString()
            );
        } catch (DataAccessException e) {
            log.error("rollback redis reservation failed, voucherId={}, userId={}", voucherId, userId, e);
        }
    }

    private String mapErrorMessage(int resultCode) {
        if (resultCode == SECKILL_NO_STOCK) {
            return "库存不足";
        }
        if (resultCode == SECKILL_DUPLICATE) {
            return "不能重复下单";
        }
        return "下单失败，请稍后重试";
    }
}
