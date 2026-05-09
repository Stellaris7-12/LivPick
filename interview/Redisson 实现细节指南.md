# Redisson 实现细节指南

## 1. 先说结论：本项目里 Redisson 用在了哪些地方

当前项目里，Redisson 主要用在 3 个方向：

1. 分布式锁
2. 延迟队列
3. 布隆过滤器

对应代码位置：

- 配置入口：[RedissonConfig](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/config/RedissonConfig.java)
- 分布式锁主使用点：[VoucherOrderServiceImpl](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java)
- 延迟队列主使用点：[OrderTimeoutDelayQueueManager](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/delay/OrderTimeoutDelayQueueManager.java)
- 布隆过滤器主使用点：[ShopBloomFilterService](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/ShopBloomFilterService.java)
- Redisson 锁测试类：[RedissonTest](C:/Users/heyunhui/IdeaProjects/LivPick/src/test/java/com/livepick/RedissonTest.java)

从业务上看：

- 分布式锁主要解决 Kafka 异步下单消费时的重复消费与并发落库问题
- 延迟队列主要解决超时未支付订单自动关单和库存回流问题
- 布隆过滤器主要解决缓存穿透问题

---

## 2. Redisson 在项目中的初始化方式

当前项目通过 Spring 容器统一创建 `RedissonClient`：

```java
@Bean
public RedissonClient redissonClient(){
    Config config = new Config();
    String address = String.format("redis://%s:%s", redisProperties.getHost(), redisProperties.getPort());
    config.useSingleServer().setAddress(address);
    if (StringUtils.hasText(redisProperties.getPassword())) {
        config.useSingleServer().setPassword(redisProperties.getPassword());
    }
    return Redisson.create(config);
}
```

这里采用的是：

- 单 Redis 节点配置
- 由 Spring Boot 的 `RedisProperties` 自动读取 `application.yaml`
- 再统一注入到业务类中使用

这样做的好处是：

- 配置集中
- 业务类只依赖 `RedissonClient`
- 后续切换到哨兵、主从、集群模式时改动较小

### 2.1 Redisson 和 Spring Data Redis / StringRedisTemplate 的基本框架

这里你可以把“String Redis Data”理解成 Spring Data Redis 体系里的 `StringRedisTemplate`。

两者的定位不一样：

- `StringRedisTemplate` 更偏底层，直接操作 String、Hash、Set、ZSet，适合做缓存、Lua、计数器、集合判重这类基础能力
- `Redisson` 更偏高级抽象，在 Redis 之上封装了 `RLock`、`RDelayedQueue`、`RBloomFilter` 这类可以直接拿来用的分布式组件

本项目里的分工可以直接这样讲：

- `StringRedisTemplate`：负责缓存读写、秒杀 Lua 原子校验、Redis 库存回补、用户下单标记集合
- `Redisson`：负责分布式锁、延迟队列、布隆过滤器

可以把它理解成两层：

- 第一层：`StringRedisTemplate`，面向 Redis 原生命令和基础数据结构
- 第二层：`Redisson`，面向业务可直接使用的分布式对象和同步原语

简单示例：

```java
// Spring Data Redis / StringRedisTemplate
stringRedisTemplate.opsForValue().set(key, value, 30, TimeUnit.MINUTES);
stringRedisTemplate.execute(seckillScript, Collections.emptyList(), voucherId, userId);

// Redisson
RLock lock = redissonClient.getLock("lock:order:" + userId);
RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("bf:shop:id");
```

### 2.2 为什么项目里两个都要用

如果只用 `StringRedisTemplate`，很多高级能力都要自己手写：

- 分布式锁要自己处理可重入、续期、Lua 解锁
- 延迟队列要自己用 `ZSet + 搬运线程` 维护
- 布隆过滤器要自己维护位图和哈希映射

如果只用 `Redisson`，缓存和 Lua 这类基础 Redis 操作又不如 `StringRedisTemplate` 直接。

所以这个项目的设计思路不是二选一，而是：

- 基础 Redis 能力交给 `StringRedisTemplate`
- 高级分布式能力交给 `Redisson`

---

## 3. 分布式锁实现细节

## 3.1 项目里哪些地方用到了锁

当前项目里，Redisson 分布式锁的核心使用点在：

- Kafka 异步消费秒杀下单消息时

对应代码：

```java
RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
boolean isLock = redisLock.tryLock();
if (!isLock) {
    log.warn("skip duplicated consume request, orderId={}, userId={}, voucherId={}, failureStage=acquireLock",
            message.getOrderId(), userId, voucherId);
    return;
}
```

锁名是：

```java
lock:order:{userId}
```

也就是说，这里加的是**用户维度锁**。

### 3.1.1 为什么按 userId 加锁

因为要解决的是：

- 同一个用户的秒杀消息重复消费
- 同一个用户在多实例消费时并发创建订单

按 `userId` 加锁的效果是：

- 同一个用户同一时刻只能有一个消费线程进入“查重 + 扣库存 + 创建订单”流程
- 不会把所有订单消费都串行化，锁粒度比较合适

### 3.1.2 这把锁是什么锁

这把锁是：

- Redis 分布式锁
- 可重入锁
- 业务维度互斥锁

它不是数据库锁，也不是 JVM 本地锁。

---

## 3.2 锁在业务流程中的位置

秒杀异步落库时，锁的完整作用位置是：

1. Kafka Consumer 收到秒杀下单消息
2. 按 `userId` 获取 Redisson 锁
3. 获取锁成功后：
   - 查数据库是否已有该用户该券订单
   - 扣减数据库库存
   - 保存订单
4. 处理完成后释放锁

对应代码核心片段：

```java
try {
    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    if (count > 0) {
        return;
    }

    boolean stockUpdated = seckillVoucherService.update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", voucherId)
            .gt("stock", 0)
            .update();

    // 保存订单
} finally {
    redisLock.unlock();
}
```

---

## 3.3 为什么这里要用 Redisson 锁

因为 Kafka 消费语义更接近至少一次投递，所以重复消费是必须考虑的。  
如果没有这把锁，就可能出现：

- 同一用户的两条重复消息同时进入落库逻辑
- 两个线程都查到“当前没有订单”
- 然后同时去扣库存、创建订单

虽然数据库唯一索引可以做最终兜底，但如果没有锁：

- 数据库冲突会更多
- 热点竞争更激烈
- 业务日志会更混乱

所以 Redisson 锁在这里的作用，不是取代数据库，而是：

- 提前降低并发冲突
- 减少重复消费带来的竞争
- 让后续查重和落库流程更稳定

---

## 3.4 Redisson 锁的底层原理

如果面试官追问 Redisson 锁底层怎么实现，可以这样回答：

### 3.4.1 加锁原理

Redisson 的 `RLock` 本质上还是基于 Redis 实现的分布式锁，但它不是简单的 `SETNX` 封装，而是做了很多增强：

- 可重入
- 自动续期
- 原子操作
- 安全释放

它底层会通过 Lua 脚本在 Redis 中原子完成加锁逻辑。  
锁数据通常不是简单字符串，而是用 **Hash 结构** 记录：

- 当前持有锁的线程标识
- 重入次数

### 3.4.2 可重入原理

如果同一个线程再次获取同一把锁，Redisson 不会直接失败，而是：

- 判断锁拥有者是不是当前线程
- 如果是，就把重入次数加一

这就是可重入锁的本质。

### 3.4.3 自动续期原理

当前项目里用的是：

```java
redisLock.tryLock();
```

这里没有显式传 `leaseTime`，所以 Redisson 默认会启用 **watchdog 机制**。

含义是：

- 初始会设置锁过期时间
- 只要当前线程还持有锁，Redisson 后台线程就会定期给锁续期
- 防止业务执行时间稍长时锁提前过期

### 3.4.4 解锁原理

Redisson 解锁也会通过 Lua 脚本保证原子性：

1. 先校验当前线程是不是锁持有者
2. 如果不是，不能删锁
3. 如果是，就减少重入计数
4. 如果重入次数减到 0，才真正删除锁
5. 删除锁后唤醒等待线程

这样可以避免误删别人的锁。

### 3.4.5 等待唤醒机制

Redisson 不只是“抢不到锁就死循环重试”，它还结合了 Pub/Sub 机制：

- 释放锁时发布通知
- 等待锁的客户端收到通知后再去竞争

这样比纯轮询更高效。

---

## 4. Redisson 延迟队列实现细节

## 4.1 用在了哪里

当前项目里，Redisson 延迟队列主要用于：

- 超时未支付订单自动关单

主入口类：

- [OrderTimeoutDelayQueueManager](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/delay/OrderTimeoutDelayQueueManager.java)

消息体：

- `OrderTimeoutMessage`

业务处理逻辑最终落在：

- `voucherOrderService.closeTimeoutOrder(orderId)`

---

## 4.2 当前实现流程

当前项目的超时关单流程是：

1. Kafka Consumer 创建订单成功
2. 构造 `OrderTimeoutMessage`
3. 调用 `orderTimeoutDelayQueueManager.offer(timeoutMessage)`
4. Redisson 延迟队列把消息延迟指定分钟数
5. 到期后消息进入阻塞队列
6. 后台消费线程 `take()`
7. 执行 `closeTimeoutOrder(orderId)`
8. 如果订单还是“未支付”，就改成“已取消”
9. 回补数据库库存
10. 回补 Redis 中的秒杀库存和用户资格

核心代码：

```java
public void offer(OrderTimeoutMessage message) {
    delayedQueue.offer(
            JSONUtil.toJsonStr(message),
            livPickProperties.getOrder().getTimeoutMinutes(),
            TimeUnit.MINUTES
    );
}
```

消费逻辑：

```java
private void consume() {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            String messageJson = blockingDeque.take();
            OrderTimeoutMessage message = JSONUtil.toBean(messageJson, OrderTimeoutMessage.class);
            voucherOrderService.closeTimeoutOrder(message.getOrderId());
        } catch (Exception e) {
            log.error("consume timeout order message failed", e);
        }
    }
}
```

### 4.2.1 当前项目里的关键参数怎么设

当前项目的相关配置是：

```yaml
livpick:
  order:
    timeout-minutes: 15
    timeout-scan-interval-ms: 60000
    delay-queue-name: order:timeout:queue
```

对应含义是：

- `timeout-minutes = 15`：订单创建 15 分钟未支付，则视为超时
- `timeout-scan-interval-ms = 60000`：SpringTask 每 60 秒做一次兜底扫描
- `delay-queue-name = order:timeout:queue`：Redisson 延迟队列名称

这组值在本项目里是比较合理的：

- 15 分钟适合作为优惠券秒杀订单的支付窗口，既不会拖太久占库存，也给用户留了足够支付时间
- 60 秒兜底扫描不会对数据库造成太大压力，同时异常情况下最多只会把关单延后到分钟级

如果面试官追问“这些值怎么定”，可以这样回答：

- 支付超时时间通常结合业务来定，电商/本地生活类未支付订单常见是 10 到 30 分钟
- 这个项目我取 15 分钟，是偏稳妥、也比较常见的中间值
- 扫描间隔我取 60 秒，因为延迟队列已经是主链路，定时任务只负责兜底，不需要扫得特别激进

### 4.2.2 能不能做到订单到期无延迟立刻关单

严格意义上做不到“绝对 0 延迟、到点瞬间完成关单”，因为它不是硬实时系统。

原因主要有几个：

- Redisson 需要把到期消息从延迟结构搬运到阻塞队列
- JVM 消费线程调度本身有时间片开销
- Redis 网络抖动、GC、CPU 抢占都会引入毫秒到秒级抖动

但在主链路正常时，它可以做到“接近到期的准实时关单”：

- 订单到期后，消息会很快进入阻塞队列
- 消费线程本身是 `take()` 阻塞等待，不需要像数据库轮询那样再等下一轮扫描
- 所以正常情况下，关单时延通常是毫秒级到秒级，而不是分钟级

这个项目里真正可能出现“最多再多等 60 秒”的，是异常兜底链路：

- 比如延迟队列消费者异常
- 服务重启时有消息没及时消费
- Redis 或网络短时抖动

这时才会由 SpringTask 在下一次扫描时补上，所以你可以把它表述成：

“主链路是 Redisson 延迟队列，正常情况下订单到期后会准实时关单；为了避免异步链路异常导致漏关单，我又加了 60 秒一次的 SpringTask 兜底扫描。”

---

## 4.3 为什么延迟队列之外还要加 Spring Task 兜底

当前项目里还额外加了一个低频扫描任务：

- [OrderTimeoutFallbackTask](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/task/OrderTimeoutFallbackTask.java)

作用是兜底。

因为任何异步链路都可能出现异常：

- 延迟队列消费者线程挂掉
- 服务重启时有消息没及时处理
- Redis / 网络短时异常

所以项目里采用的是：

- Redisson 延迟队列做主方案
- Spring Task 定时扫描做兜底方案

这也是面试里很加分的一点，因为它说明你没有把异步链路想得过于理想化。

你可以这样表述：

“延迟队列负责按时调度，大多数超时订单都会按时消费；定时任务负责扫漏，避免极端情况下有订单长期不关闭。”

### 4.3.1 SpringTask 的具体实现

调度入口很简单：

```java
@Scheduled(fixedDelayString = "${livpick.order.timeout-scan-interval-ms}")
public void scanTimeoutOrders() {
    voucherOrderService.scanAndCloseTimeoutOrders();
}
```

Service 层真正的扫描逻辑是：

```java
LocalDateTime expireBefore = LocalDateTime.now().minusMinutes(livPickProperties.getOrder().getTimeoutMinutes());
List<VoucherOrder> timeoutOrders = query()
        .eq("status", OrderStatusConstants.UNPAID)
        .lt("create_time", expireBefore)
        .last("LIMIT 100")
        .list();
timeoutOrders.forEach(order -> closeTimeoutOrder(order.getId()));
```

这个实现里有 3 个点值得讲：

- 只扫 `status = UNPAID`，避免重复处理已支付或已取消订单
- 用 `create_time < now - timeoutMinutes` 判断是否超时
- 每次 `LIMIT 100`，避免一次扫描拉太多数据，先控制住兜底任务的数据库压力

### 4.3.2 为什么这里用 fixedDelay，而不是 fixedRate

这里用 `fixedDelay` 更稳。

原因是：

- `fixedDelay` 是“上一次执行完成后，再等待一段时间执行下一次”
- 如果某次扫描比较慢，不会和下一次扫描重叠
- 对这种兜底型任务，更重要的是稳定和不重叠，而不是绝对固定节拍

如果用 `fixedRate`，在扫描耗时变长时更容易出现任务重叠，反而会带来重复扫描和额外数据库压力。

### 4.3.3 SpringTask 多久扫一次比较合适

这个项目当前取的是 `60000ms`，也就是 60 秒一次，我认为这是比较合适的默认值。

可以按下面的口径回答：

- 兜底任务不是主链路，所以不需要扫得特别频繁
- 30 到 60 秒通常是比较均衡的区间
- 如果业务量很小，1 到 2 分钟也可以接受
- 如果对超时关单的时效要求更高，可以压到 10 到 30 秒，但数据库扫描压力会更大
- 一般不建议小于 5 秒，否则很容易把兜底任务做成高频轮询

所以这个项目选 60 秒，本质上是一个“异常场景分钟级兜底、数据库压力可控”的折中值。

---

## 4.4 Redisson 延迟队列底层原理

如果面试官追问 Redisson 延迟队列底层是怎么做的，可以这样回答：

Redisson 延迟队列本质上不是 Kafka 那种真正的 broker 消息队列，而是**基于 Redis 数据结构模拟出的延迟调度能力**。

底层核心思路是：

- 延迟消息先按到期时间存储
- Redisson 后台调度线程会把“已到期”的消息搬运到可消费队列
- 业务线程再从阻塞队列里消费

可以理解成：

- 一层负责“时间调度”
- 一层负责“到期可消费”

所以它非常适合：

- 超时关单
- 延迟通知
- 小中型项目的延迟任务

但它不是专业 MQ 的延迟消息替代品，后续业务规模特别大时，还可以继续升级到 Kafka/RocketMQ 专门的消息体系。

---

## 5. Redisson Bloom Filter 实现细节

## 5.1 用在了哪里

当前项目里，Redisson 布隆过滤器主要用于：

- 店铺查询防缓存穿透

核心类：

- [ShopBloomFilterService](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/ShopBloomFilterService.java)
- [CacheClient](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/CacheClient.java)
- [ShopServiceImpl](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/ShopServiceImpl.java)

---

## 5.2 当前实现流程

应用启动时：

1. 获取 `RBloomFilter<String>`
2. 调用 `tryInit(expectedInsertions, falseProbability)`
3. 如果过滤器为空，则从数据库装载店铺 ID

核心代码：

```java
shopBloomFilter = redissonClient.getBloomFilter(livPickProperties.getBloom().getShopFilterName());
shopBloomFilter.tryInit(
        livPickProperties.getBloom().getExpectedInsertions(),
        livPickProperties.getBloom().getFalseProbability()
);
if (shopBloomFilter.count() == 0) {
    rebuild();
}
```

### 5.2.1 当前项目里的布隆过滤器参数

当前项目配置是：

```yaml
livpick:
  bloom:
    shop-filter-name: bf:shop:id
    expected-insertions: 100000
    false-probability: 0.03
```

这几个值的含义是：

- `shop-filter-name = bf:shop:id`：店铺 ID 布隆过滤器在 Redis 中的 key
- `expected-insertions = 100000`：预估会放入 10 万个店铺 ID
- `false-probability = 0.03`：容忍 3% 的误判率

### 5.2.2 expectedInsertions 怎么选

`expectedInsertions` 本质上是你对“未来一段时间数据规模”的预估。

常见选择原则是：

- 不要只按当前数据量取值，要给未来增长留余量
- 如果当前真实数据量是 1 万级，可以配到 5 万到 10 万
- 如果当前真实数据量已经接近配置值，误判率就会越来越高，需要尽快扩容或重建

这个项目里我把它设成 `100000`，可以这样解释：

- 本地生活平台的店铺量通常不会像订单那样爆炸增长
- 但我不想把布隆过滤器配得刚刚好，所以给了一个比较宽松的增长空间
- 这样可以在内存成本和后续扩展之间做平衡

### 5.2.3 falseProbability 怎么选

`falseProbability` 决定的是误判率。

常见取值思路：

- `0.1` 太高，一般不建议
- `0.03` 到 `0.01` 是比较常见的业务区间
- `0.001` 误判率更低，但占用内存会明显增大

这个项目选 `0.03`，是因为：

- 布隆过滤器误判的结果，不是直接出错，而是“多放过一小部分请求去走缓存/数据库”
- 后面还有缓存空值兜底
- 所以这里不需要把误判率压得特别极致

如果面试官问“为什么不是 0.01”，可以回答：

- 0.01 当然也可以，过滤效果更好
- 但当前项目的数据规模不大，而且后面还有缓存空值兜底
- 所以我先选了 0.03，降低一点内存开销，已经够用

### 5.2.4 布隆过滤器的重建策略

布隆过滤器有一个很重要的特点：**能新增，不能安全删除**。

所以完整策略通常不是只初始化一次，而是分成两部分：

- 增量维护：新增店铺时，调用 `addShop(shopId)` 把新 ID 加进去
- 全量重建：低峰期重新扫描数据库，重建整张过滤器

当前项目里已经有：

- 应用启动时 `tryInit`
- 过滤器为空时执行 `rebuild()`
- 新增数据可走 `addShop()`

如果从完整生产策略来讲，我会推荐：

- 店铺数据变更不频繁时：每周重建一次
- 店铺数据有持续新增或删除时：每天凌晨低峰期重建一次，比如 `03:00`
- 如果发现当前实际数据量已经接近 `expectedInsertions`，也要提前触发重建并调大参数

这类本地生活店铺数据通常不是高频变更，所以面试里说“新增增量写入 + 每天凌晨低峰重建一次”会比较稳。

查询时：

1. 先查布隆过滤器
2. 如果过滤器判断“不存在”，直接返回空
3. 如果判断“可能存在”，再进入 Redis 缓存查询
4. Redis 未命中时查数据库
5. 数据不存在则写入空值缓存

`ShopServiceImpl` 中的调用：

```java
Shop shop = cacheClient.queryWithBloomPassThrough(
        CACHE_SHOP_KEY, id, shopBloomFilterService::mightContain, Shop.class,
        this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES
);
```

---

## 5.3 为什么要“布隆过滤器 + 缓存空值”组合

因为这两个方案各自都有局限：

### 5.3.1 只用布隆过滤器的问题

- 有误判
- 不能保证百分之百精准
- 少量不存在的 key 可能仍然会穿到数据库

### 5.3.2 只用缓存空值的问题

- 第一次无效请求还是会打到数据库
- 如果有大量随机不存在请求，数据库仍然会承压

### 5.3.3 组合后的效果

- 绝大多数无效请求先被布隆过滤器挡掉
- 少量漏过的请求再由缓存空值兜底

这就是当前项目选择组合方案的原因。

### 5.3.4 为什么还需要重建，而不是永远增量加

因为布隆过滤器不擅长删除。

如果店铺下线、删除、迁移很多，只做增量新增会带来两个问题：

- 已删除 ID 仍然可能被判断为“可能存在”
- 随着元素越来越多，误判率会逐步上升

所以完整方案一般是：

- 平时增量新增
- 低峰期定期全量重建

这样可以兼顾性能和准确性。

---

## 5.4 布隆过滤器底层原理

如果面试官追问原理，可以这样答：

布隆过滤器本质上是：

- 一个位数组
- 多个哈希函数

插入元素时：

1. 用多个哈希函数对元素做映射
2. 得到多个下标
3. 把这些位置都置为 `1`

查询元素时：

1. 对同一个元素再次做多次哈希
2. 看这些位置是不是都为 `1`
3. 如果有一个位置不是 `1`，那它一定不存在
4. 如果全部都是 `1`，说明它“可能存在”

所以布隆过滤器的性质是：

- 不存在的一定不存在
- 存在的可能存在

也就是说：

- 可能误判
- 不会漏判

这也是它能用来做缓存穿透预过滤的根本原因。

---

## 6. 为什么本项目要优先用 Redisson

## 6.1 不是不能手写，而是 Redisson 更适合当前阶段

理论上，这些能力都可以自己基于 Redis 手写：

- 分布式锁：`SETNX + EXPIRE + Lua 解锁`
- 延迟队列：`ZSet + 定时搬运`
- 布隆过滤器：`Bitmap + Hash`

但如果都自己写，会有几个问题：

- 代码量大
- 边界条件多
- 容易埋坑
- 面试时很难把“业务能力”和“底层实现”都兼顾好

Redisson 的价值就在于：

- 对 Redis 常用高级能力做了成熟封装
- API 面向对象，业务代码更清晰
- 底层已经处理了很多原子性、续期、唤醒、序列化等细节

所以当前项目选择 Redisson，本质上是：

- 把精力更多放在业务流程和架构设计上
- 不在底层重复造轮子

---

## 6.2 为什么不是直接用 Spring Data Redis

Spring Data Redis 更偏底层操作：

- 适合直接操作 String、Hash、Set、ZSet、Bitmap
- 适合手写 Lua 和缓存逻辑

但它没有像 Redisson 这样现成的：

- `RLock`
- `RDelayedQueue`
- `RBloomFilter`

所以在当前项目里，两者的分工更合理：

- Spring Data Redis：做缓存、Lua、基础 Redis 数据结构操作
- Redisson：做高级同步原语和高级分布式数据结构

---

## 7. 面试时怎么整体讲 Redisson

你可以用下面这段作为整体回答模板：

“在这个项目里，Redisson 主要用在三块。第一块是 Kafka 异步下单消费时的分布式锁，我按 userId 加的是用户维度可重入锁，用来降低重复消费和并发落库冲突；第二块是超时关单，我用的是 Redisson 延迟队列，订单创建成功后投递延迟消息，到期后自动消费并关单，同时再用 Spring Task 做兜底扫描；第三块是缓存穿透防护，我用 Redisson 的 Bloom Filter 存储店铺 ID，查询时先过过滤器，再走缓存空值兜底。

之所以选 Redisson，是因为它把 Redis 上常见的高级能力，比如分布式锁、延迟队列、布隆过滤器，都封装成了成熟 API。这样我可以把精力放在业务链路设计上，而不是反复手写底层轮子。底层原理上，分布式锁是基于 Redis + Lua 实现的可重入锁和安全释放，延迟队列是基于 Redis 做延迟调度和到期搬运，布隆过滤器本质上是位图加多哈希函数。” 

---

## 8. 面试高频追问与回答方向

### 8.1 为什么锁按 userId 加，而不是 voucherId 加

因为当前要解决的是“同一用户重复消费、重复下单”的问题。  
按 `userId` 加锁可以把同一用户的重复消息串行化，而不会把所有用户对同一券的消费都锁死。

### 8.2 Redisson 锁和数据库唯一索引是不是重复了

不是重复，而是两层保护。  
Redisson 锁负责提前降低并发竞争和重复消费冲突；数据库唯一索引负责最终一致性的兜底。

### 8.3 延迟队列为什么还要 Spring Task

因为异步链路可能异常，单靠延迟队列不够稳。  
Spring Task 扫描任务负责兜底，把漏处理的超时订单补回来。

### 8.4 布隆过滤器为什么不能单独使用

因为它有误判，只能保证“不存在的一定不存在”，不能保证“存在的一定存在”。  
所以还要结合缓存空值，兜住漏网请求。

### 8.5 Redisson 的缺点是什么

- 对 Redis 依赖更强
- 高级封装背后细节更多，调优时要理解内部机制
- 延迟队列不适合替代大型 MQ 的全部能力

但对当前这个项目来说，收益明显大于成本。
