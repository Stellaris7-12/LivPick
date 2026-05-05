package com.livepick;

import com.livepick.entity.Shop;
import com.livepick.service.impl.ShopServiceImpl;
import com.livepick.utils.CacheClient;
import com.livepick.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.livepick.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.livepick.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class LivPickApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

/**
 * 测试ID生成器性能的测试方法
 * 使用多线程并发生成ID并计算总耗时
 * @throws InterruptedException 如果线程被中断
 */
    @Test
    void testIdWorker() throws InterruptedException {
        // 创建一个倒计数闩锁，初始值为300
        // 用于等待所有线程完成执行
        CountDownLatch latch = new CountDownLatch(300);

        // 定义一个任务，每个线程将执行此任务
        Runnable task = () -> {
            // 每个线程生成100个ID
            for (int i = 0; i < 100; i++) {
                // 调用ID生成器生成一个新的ID
                long id = redisIdWorker.nextId("order");
                // 打印生成的ID
                System.out.println("id = " + id);
            }
            // 线程完成任务后，倒计数闩锁减1
            latch.countDown();
        };
        // 记录开始时间
        long begin = System.currentTimeMillis();
        // 使用线程池提交300个任务
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        // 等待所有线程完成
        latch.await();
        // 记录结束时间
        long end = System.currentTimeMillis();
        // 打印总耗时
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
