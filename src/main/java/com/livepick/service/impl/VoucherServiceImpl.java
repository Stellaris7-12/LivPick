package com.livepick.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.livepick.dto.Result;
import com.livepick.entity.SeckillVoucher;
import com.livepick.entity.Voucher;
import com.livepick.mapper.VoucherMapper;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.IVoucherService;
import com.livepick.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.livepick.utils.RedisConstants.CACHE_VOUCHER_LIST_KEY;
import static com.livepick.utils.RedisConstants.CACHE_VOUCHER_LIST_TTL;
import static com.livepick.utils.RedisConstants.LOCK_VOUCHER_LIST_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = cacheClient.queryListWithLogicalExpire(
                CACHE_VOUCHER_LIST_KEY,
                LOCK_VOUCHER_LIST_KEY,
                shopId,
                Voucher.class,
                id -> {
                    List<Voucher> result = getBaseMapper().queryVoucherOfShop(id);
                    return result == null ? Collections.emptyList() : result;
                },
                CACHE_VOUCHER_LIST_TTL,
                TimeUnit.MINUTES
        );
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addVoucher(Voucher voucher) {
        save(voucher);
        clearVoucherListCache(voucher.getShopId());
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到Redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        clearVoucherListCache(voucher.getShopId());
    }

    private void clearVoucherListCache(Long shopId) {
        if (shopId == null) {
            return;
        }
        stringRedisTemplate.delete(CACHE_VOUCHER_LIST_KEY + shopId);
    }
}
