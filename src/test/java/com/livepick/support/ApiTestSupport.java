package com.livepick.support;

import com.livepick.entity.Shop;
import com.livepick.entity.Voucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.mapper.VoucherOrderMapper;
import com.livepick.service.IShopService;
import com.livepick.service.IVoucherService;
import com.livepick.utils.OrderStatusConstants;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.livepick.utils.RedisConstants.LOGIN_USER_KEY;
import static com.livepick.utils.RedisConstants.LOGIN_USER_TTL;
import static com.livepick.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_REORDER_KEY;
import static com.livepick.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public abstract class ApiTestSupport {

    @Resource
    protected MockMvc mockMvc;

    @Resource
    protected StringRedisTemplate stringRedisTemplate;

    @Resource
    protected IShopService shopService;

    @Resource
    protected IVoucherService voucherService;

    @Resource
    protected VoucherOrderMapper voucherOrderMapper;

    private final Set<String> cleanupRedisKeys = new LinkedHashSet<>();

    @AfterEach
    void cleanupRedisKeys() {
        if (!cleanupRedisKeys.isEmpty()) {
            stringRedisTemplate.delete(cleanupRedisKeys);
            cleanupRedisKeys.clear();
        }
    }

    protected Shop requireAnyShop() {
        Shop shop = shopService.lambdaQuery().last("limit 1").one();
        assertNotNull(shop, "test data requires at least one shop record");
        return shop;
    }

    protected Voucher createSeckillVoucherFixture(String titlePrefix) {
        Shop shop = requireAnyShop();
        Voucher voucher = new Voucher();
        voucher.setShopId(shop.getId());
        voucher.setTitle(titlePrefix + "-" + System.currentTimeMillis());
        voucher.setSubTitle("api-test");
        voucher.setRules("api-test-rules");
        voucher.setPayValue(100L);
        voucher.setActualValue(1000L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucher.setStock(10);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(5));
        voucher.setEndTime(LocalDateTime.now().plusDays(1));
        voucher.setCreateTime(LocalDateTime.now());
        voucher.setUpdateTime(LocalDateTime.now());
        voucherService.addSeckillVoucher(voucher);

        registerCleanupKey(SECKILL_STOCK_KEY + voucher.getId());
        registerCleanupKey(SECKILL_ORDER_KEY + voucher.getId());
        return voucher;
    }

    protected VoucherOrder createUnpaidOrderFixture(Long voucherId, Long userId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(System.currentTimeMillis());
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setPayType(1);
        order.setStatus(OrderStatusConstants.UNPAID);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        voucherOrderMapper.insert(order);
        return order;
    }

    protected String prepareLoginToken(Long userId) {
        String token = "test-token-" + userId + "-" + System.nanoTime();
        String key = LOGIN_USER_KEY + token;
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userId));
        userMap.put("nickName", "api-test-user");
        userMap.put("icon", "");
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        registerCleanupKey(key);
        return token;
    }

    protected void clearSeckillReservation(Long voucherId, Long userId) {
        stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + voucherId, String.valueOf(userId));
        stringRedisTemplate.delete(SECKILL_REORDER_KEY + voucherId + ":" + userId);
    }

    protected VoucherOrder createCancelledOrderFixture(Long voucherId, Long userId) {
        VoucherOrder order = createUnpaidOrderFixture(voucherId, userId);
        order.setStatus(OrderStatusConstants.CANCELLED);
        order.setRefundTime(LocalDateTime.now());
        voucherOrderMapper.updateById(order);
        return order;
    }

    protected void registerCleanupKey(String key) {
        cleanupRedisKeys.add(key);
    }

    protected String benchmarkUserIdHeader(Long userId) {
        return String.valueOf(userId);
    }
}
