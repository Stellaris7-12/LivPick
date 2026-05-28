package com.livepick.service;

import com.livepick.dto.Result;
import com.livepick.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addVoucher(Voucher voucher);

    void addSeckillVoucher(Voucher voucher);
}
