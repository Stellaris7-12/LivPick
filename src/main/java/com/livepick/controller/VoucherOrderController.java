package com.livepick.controller;


import com.livepick.dto.Result;
import com.livepick.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("pay/{id}")
    public Result payOrder(@PathVariable("id") Long orderId) {
        boolean success = voucherOrderService.payOrder(orderId);
        return success ? Result.ok() : Result.fail("订单状态已变化，支付失败");
    }
}
