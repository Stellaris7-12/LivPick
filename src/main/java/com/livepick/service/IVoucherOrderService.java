package com.livepick.service;

import com.livepick.dto.Result;
import com.livepick.entity.VoucherOrder;
import com.livepick.mq.message.SeckillOrderMessage;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(SeckillOrderMessage message);

    boolean payOrder(Long orderId);
}
