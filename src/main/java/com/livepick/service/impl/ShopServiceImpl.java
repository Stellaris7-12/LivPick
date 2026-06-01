package com.livepick.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.livepick.dto.Result;
import com.livepick.entity.Shop;
import com.livepick.mapper.ShopMapper;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.mq.producer.LivPickKafkaProducer;
import com.livepick.service.IShopService;
import com.livepick.service.ShopBloomFilterService;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import com.livepick.utils.CacheClient;
import com.livepick.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.livepick.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.livepick.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.livepick.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopBloomFilterService shopBloomFilterService;
    @Resource
    private LivPickKafkaProducer livPickKafkaProducer;
    @Resource
    private BenchmarkRuntimeConfigService benchmarkRuntimeConfigService;
    @Resource
    private BenchmarkMetricsService benchmarkMetricsService;

    @Override
    public Result queryById(Long id) {
        return queryByIdWithMode(id, benchmarkRuntimeConfigService.isCachePenetrationProtectionEnabled());
    }

    @Override
    public Result queryByIdWithMode(Long id, boolean useBloomProtection) {
        long startTime = System.nanoTime();
        Shop shop = useBloomProtection
                ? cacheClient.queryWithBloomPassThrough(
                CACHE_SHOP_KEY, id, shopBloomFilterService::mightContain, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES
        )
                : cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
        benchmarkMetricsService.recordCacheLatency(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        String cacheKey = CACHE_SHOP_KEY + id;
        try {
            cacheClient.delete(cacheKey);
        } catch (Exception e) {
            CacheDeleteRetryMessage message = new CacheDeleteRetryMessage();
            message.setCacheKey(cacheKey);
            message.setBizType("SHOP");
            message.setBizId(id);
            message.setRetryCount(0);
            try {
                livPickKafkaProducer.sendCacheDeleteRetry(message);
            } catch (Exception producerException) {
                throw new RuntimeException("send cache delete retry message failed", producerException);
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
