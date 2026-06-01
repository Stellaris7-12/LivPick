package com.livepick.service;

import com.livepick.config.LivPickProperties;
import com.livepick.entity.Shop;
import com.livepick.mapper.ShopMapper;
import com.livepick.service.benchmark.BenchmarkMetricsService;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopBloomFilterService {

    private final RedissonClient redissonClient;
    private final ShopMapper shopMapper;
    private final LivPickProperties livPickProperties;
    private final BenchmarkMetricsService benchmarkMetricsService;
    private final BenchmarkRuntimeConfigService runtimeConfigService;

    private RBloomFilter<String> shopBloomFilter;

    @PostConstruct
    public void init() {
        shopBloomFilter = redissonClient.getBloomFilter(livPickProperties.getBloom().getShopFilterName());
        shopBloomFilter.tryInit(
                livPickProperties.getBloom().getExpectedInsertions(),
                livPickProperties.getBloom().getFalseProbability()
        );
        if (shopBloomFilter.count() == 0) {
            rebuild();
        }
    }

    public boolean mightContain(Long shopId) {
        if (!runtimeConfigService.isCachePenetrationProtectionEnabled()) {
            benchmarkMetricsService.incrementBloomPassed();
            return true;
        }
        boolean contains = shopBloomFilter.contains(String.valueOf(shopId));
        if (contains) {
            benchmarkMetricsService.incrementBloomPassed();
        } else {
            benchmarkMetricsService.incrementBloomRejected();
        }
        return contains;
    }

    public void addShop(Long shopId) {
        shopBloomFilter.add(String.valueOf(shopId));
    }

    public void rebuild() {
        shopBloomFilter.delete();
        shopBloomFilter = redissonClient.getBloomFilter(livPickProperties.getBloom().getShopFilterName());
        shopBloomFilter.tryInit(
                livPickProperties.getBloom().getExpectedInsertions(),
                livPickProperties.getBloom().getFalseProbability()
        );
        List<Shop> shops = shopMapper.selectList(null);
        shops.forEach(shop -> shopBloomFilter.add(String.valueOf(shop.getId())));
        log.info("shop bloom filter initialized, count={}", shopBloomFilter.count());
    }
}
