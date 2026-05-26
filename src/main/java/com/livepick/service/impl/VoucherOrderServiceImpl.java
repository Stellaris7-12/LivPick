package com.livepick.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.livepick.dto.Result;
import com.livepick.entity.SeckillVoucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.IVoucherOrderService;
import com.livepick.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        if (UserHolder.getUser() == null) {
            return Result.fail("User is not logged in");
        }
        Long userId = UserHolder.getUser().getId();
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("Voucher not found");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("Seckill has not started");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("Seckill has ended");
        }

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("Duplicate orders are not allowed");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("Out of stock");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setPayType(1);
        voucherOrder.setStatus(1);
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("duplicate seckill order blocked, userId={}, voucherId={}", userId, voucherId);
            return Result.fail("Duplicate orders are not allowed");
        }

        return Result.ok(voucherOrder.getId());
    }
}
