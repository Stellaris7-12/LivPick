# Redisson 与 Kafka 项目理解强化

## 1. 先抓主线

这个项目里，秒杀相关链路可以概括成一句话：

`Redis + Lua` 负责前置拦截和资格预扣，`Kafka` 负责异步削峰和落库解耦，`Redisson` 负责延迟任务和并发控制，`MySQL` 负责最终落地和一致性兜底。

面试时不要把这些能力拆得太散，最好始终围绕 3 条主线讲：

1. 秒杀流程优化：为什么要从同步改成 `Redis + Kafka` 的异步链路
2. 未支付订单到期自动关闭：为什么要用 `Redisson 延迟队列 + SpringTask`
3. 数据一致性保证：为什么不用分布式事务，而是用补偿、幂等和兜底

---

## 2. 秒杀流程优化

### 2.1 目标是什么

这块不是简单地“把下单改成异步”，而是解决同步直写数据库的问题：

- 活动开始瞬间大量请求直打数据库
- 秒杀券是热点行，库存扣减竞争非常激烈
- 请求线程会被数据库 IO 阻塞
- 一人一单和库存扣减要一起考虑

所以最终把链路拆成两段：

1. Redis 入口做原子资格校验和预扣
2. Kafka 异步落库，由消费端做最终下单

### 2.2 主流程

1. 用户发起秒杀请求
2. 服务执行 Lua 脚本，原子校验库存和一人一单
3. Redis 校验通过后，生成订单消息发送到 Kafka
4. Kafka Consumer 消费消息，执行数据库扣库存和订单落库
5. 订单创建成功后，再投递 Redisson 延迟队列，等待后续超时关单

### 2.3 为什么这样做

- `Redis + Lua` 把高并发资格校验前置到内存，减少数据库压力
- `Kafka` 把瞬时写流量削成平滑消费流量
- `MySQL` 只承担最终落库，不再承担所有前置资格判断
- 这样系统吞吐、可用性和数据库稳定性都会更好

### 2.4 技术原理

#### Redis + Lua

核心是把“判断库存、判断一人一单、预扣库存、记录下单资格”放在一个 Lua 脚本里原子执行。  
这样不会出现“刚判断完库存够，下一步还没扣减就被别人抢光”的并发窗口。

#### Kafka

Kafka 在这里解决的是同步流程的两个问题：

- 削峰：把瞬时高并发请求变成可控的消费速率
- 解耦：用户线程不需要等待数据库落库完成

#### 幂等

Kafka 不能天然帮你解决所有重复消费问题，所以业务层要自己做幂等。  
这个项目里用了 3 层保护：

1. Redis Lua 入口挡掉重复请求
2. Kafka 消费端用 `Redisson` 按 `userId` 加锁
3. 数据库 `(voucher_id, user_id)` 唯一索引兜底

---

## 3. 未支付订单到期自动关闭

### 3.1 目标是什么

订单创建成功后，如果用户一直不支付，库存会被一直占着。  
所以必须有一套机制在订单超时后自动关闭订单，并回补库存。

### 3.2 主流程

1. 订单创建成功
2. 构造 `OrderTimeoutMessage`
3. 投递到 `Redisson` 延迟队列
4. 到期后由后台消费线程取出消息
5. 尝试关闭订单
6. 如果订单仍然是未支付状态，就改成已取消
7. 回补 MySQL 库存
8. 回补 Redis 预扣库存，并移除该用户下单资格标记

### 3.3 为什么要加 SpringTask 兜底

延迟队列是主方案，但异步链路不可能 100% 不出问题。  
所以又加了一层低频扫描兜底：

- 消费线程异常退出
- 服务重启期间有消息没及时处理
- Redis 或网络短时抖动

这时候就靠 SpringTask 扫描超时未支付订单，把漏处理的订单补回来。

### 3.4 技术原理

#### Redisson 延迟队列

`RDelayedQueue` 本质上不是专业 MQ，而是基于 Redis 数据结构实现的延迟调度能力。

思路是：

1. 先把消息按到期时间存起来
2. Redisson 到期后把消息搬运到阻塞队列
3. 业务线程从阻塞队列里消费

所以它适合：

- 超时关单
- 延迟通知
- 中小型项目的延迟任务

#### SpringTask

兜底任务的作用不是代替延迟队列，而是扫漏。  
当前项目配置是每 `60000ms` 扫描一次，只查 `UNPAID` 且创建时间已经超出 `15` 分钟的订单。

---

## 4. 数据一致性保证

### 4.1 秒杀订单链路的一致性

这条链路不是分布式事务，而是最终一致性方案。

核心思路是：

- Redis 先预扣库存和资格
- Kafka 异步落库
- 发送消息失败就回滚 Redis 预扣
- 消费落库失败则依赖重试或回补逻辑
- 重复消费依赖 Redisson 锁和数据库唯一索引幂等
- 订单未支付超时后再通过延迟队列回补库存

也就是说，你的一致性不是靠 2PC，而是靠：

- 前置原子校验
- 异步补偿
- 幂等控制
- 最终兜底

### 4.2 店铺缓存链路的一致性

店铺更新走的是典型的旁路缓存模式：

1. 先更新数据库
2. 再删除缓存
3. 如果删缓存失败，就发 Kafka 补偿消息
4. Consumer 消费补偿消息后再删一次
5. 超过最大重试次数则打错误日志

这样做的原因是：

- 旁路缓存简单直接
- 比起延迟双删，更容易控制和讲清楚
- 比起 canal，当前项目实现成本更低，也更贴合现有 Kafka 技术栈

---

## 5. Redis 数据结构与字段设计

这一节很适合回答面试官对“Redis 里到底存了什么”的追问。

### 5.1 秒杀模块在 Redis 里用了哪些数据结构

当前项目在秒杀链路里，Redis 主要用了 3 类结构：

1. `String`
2. `Set`
3. `String` 自增计数器

#### 1. 库存：`String`

优惠券库存使用 `String` 类型存储，key 形如：

```text
seckill:stock:{voucherId}
```

值就是当前可用库存数量。

之所以用 `String`，是因为库存操作非常简单，核心就是：

- 查询库存：`GET`
- 扣减库存：`INCRBY -1`
- 回补库存：`INCRBY 1`

这类“单值计数”场景，用 `String` 最直接。

#### 2. 已下单用户：`Set`

已下单用户使用 `Set` 类型存储，key 形如：

```text
seckill:order:{voucherId}
```

value 是该券已经抢到资格的 `userId` 集合。

之所以用 `Set`，是因为这里的核心需求就是判断用户是否已经下过单：

- 判断是否已下单：`SISMEMBER`
- 记录下单资格：`SADD`
- 回滚或关单时移除资格：`SREM`

这比自己维护一串字符串或列表更合适，因为集合天然支持“去重”和“成员判断”。

#### 3. 分布式 ID 序列：`String` 自增计数器

订单 ID 不是数据库自增，也不是雪花算法库直接生成，而是通过 [RedisIdWorker](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/RedisIdWorker.java) 生成的分布式 ID。

它底层也是基于 Redis 的 `String` 自增：

```java
long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
return timestamp << COUNT_BITS | count;
```

也就是说：

- 高位是时间戳
- 低位是 Redis 每日自增序列

这样可以保证：

- 全局唯一
- 趋势递增
- 不依赖数据库生成主键

### 5.2 秒杀模块里 Redis 最终到底存了哪些字段

如果面试官问“秒杀模块 Redis 最终是不是只存了 `order_id、user_id、voucher_id`”，你要区分 Redis 和消息队列。

#### Redis 里当前实际存储的，不是完整订单对象

当前 Redis 里在秒杀模块真正长期参与业务判断的，主要是：

- `seckill:stock:{voucherId}` -> 库存数
- `seckill:order:{voucherId}` -> 已下单用户 `userId` 集合

也就是说，**当前秒杀 Redis 并没有把完整订单对象存进去**，也没有把 `orderId、userId、voucherId` 作为一个 Redis Hash 或 JSON 订单实体存进去。

#### `orderId` 在秒杀入口阶段的作用

`orderId` 是在 Java 侧先生成好的，然后主要用于：

- 作为订单主键
- 写入 Kafka 消息
- 后续数据库落库使用

值得注意的是，当前 `seckill.lua` 虽然调用时传入了 `orderId`，但脚本本身并没有使用这个参数。  
也就是说，当前脚本真正依赖的是：

- `voucherId`
- `userId`

而不是把 `orderId` 写进 Redis。

### 5.3 秒杀消息队列里投送了哪些字段

当前秒杀下单消息体是 [SeckillOrderMessage](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/message/SeckillOrderMessage.java)：

```java
public class SeckillOrderMessage {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private LocalDateTime createTime;
}
```

所以当前 Kafka 里真正投送的字段是：

- `orderId`
- `userId`
- `voucherId`
- `createTime`

这里每个字段的作用分别是：

- `orderId`：数据库订单主键，也用作 Kafka message key
- `userId`：消费端做 Redisson 锁和查重
- `voucherId`：消费端扣数据库库存、构建订单
- `createTime`：保证数据库订单时间和延迟关单时间计算有统一依据

### 5.4 秒杀模块为什么不直接把完整订单存在 Redis

因为 Redis 在这个链路里的职责不是“订单主存储”，而是：

- 前置资格校验
- 承接高并发
- 做短链路原子判断

真正的订单实体，包括：

- 订单状态
- 支付方式
- 支付时间
- 取消时间

这些都更适合落在 MySQL 中管理。

所以这套架构里 Redis 更像是“高并发闸门”，MySQL 才是“订单事实存储”。

### 5.5 缓存模块里应该用哪些 Redis 数据类型

这个问题最好分成“当前项目已经这样用”和“后续热点页可怎么设计”两部分回答。

#### 1. 店铺详情缓存：`String`

当前项目店铺详情缓存是典型的 `String` 存 JSON，key 形如：

```text
cache:shop:{shopId}
```

缓存值有两种形态：

- 普通 JSON 对象
- 带逻辑过期时间的 `RedisData`

对应代码在 [CacheClient](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/CacheClient.java)：

```java
public void set(String key, Object value, Long time, TimeUnit unit) {
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
}

public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
    RedisData redisData = new RedisData();
    redisData.setData(value);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
}
```

之所以选 `String`，是因为：

- 店铺详情是按 key 直接读取的对象缓存
- 读写都比较直接
- 序列化成 JSON 后非常适合旁路缓存模式

#### 2. 店铺地理位置：`GEO`

店铺按位置查询时，项目已经使用了 Redis GEO，key 形如：

```text
shop:geo:{typeId}
```

对应代码在 [ShopServiceImpl](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/ShopServiceImpl.java)：

```java
GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
        .search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
```

这里本质上存的是：

- `member`：店铺 `shopId`
- `score/geo信息`：经纬度

适合“附近店铺”这种按坐标半径搜索的场景。

#### 3. 热点优惠券页面：优先用 `String`，按需要扩展 `ZSet`

如果你后续要补“优惠券热点页缓存”，建议先按两层来设计：

第一层是页面对象缓存，优先用 `String`：

- `cache:voucher:detail:{voucherId}`：缓存单个优惠券详情
- `cache:voucher:list:{typeId}:{page}`：缓存某类券列表页

原因是：

- 页面接口大多是整页返回 DTO
- 直接缓存 JSON 最容易落地
- 对于热点详情页和热点列表页，这种方式最简单

第二层是排序或榜单场景，可以考虑 `ZSet`：

- `voucher:rank:sales`
- `voucher:rank:hot`

`ZSet` 的 value 可以存 `voucherId`，score 可以存：

- 销量
- 热度分
- 最近访问量

这样适合：

- 热门券排行榜
- 限时活动排序
- 按热度动态展示

#### 4. 布隆过滤器：Redisson `RBloomFilter`

这不是原生 `StringRedisTemplate` 直接操作的数据结构，但从 Redis 视角看，它底层仍然是位图/位数组语义。

在项目中，它存的是“可能存在的店铺 ID 集合”，不是完整对象。

所以你可以这样概括：

- 对象缓存：`String`
- 地理位置：`GEO`
- 秒杀库存：`String`
- 秒杀用户资格：`Set`
- 热门榜单：`ZSet`
- 穿透防护：`Bloom Filter`

---

## 6. 关键代码逐个过一遍

下面这些文件，是你面试时最值得重点看的代码。

### 6.1 [VoucherOrderServiceImpl](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java)

这个类是秒杀主链路的核心，包含 4 个关键动作：

- 秒杀入口：Redis + Lua + 发送 Kafka
- 消费落库：Redisson 锁 + DB 扣库存 + 创建订单
- 超时关单：关闭订单并回补库存
- 支付并发控制：状态 CAS

#### 秒杀入口核心代码

```java
Long result = stringRedisTemplate.execute(
        SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(), userId.toString(), String.valueOf(orderId)
);
if (executeResult != 0) {
    return Result.fail(executeResult == 1 ? "库存不足" : "不能重复下单");
}

SeckillOrderMessage message = new SeckillOrderMessage();
message.setOrderId(orderId);
message.setUserId(userId);
message.setVoucherId(voucherId);
message.setCreateTime(LocalDateTime.now());
livPickKafkaProducer.sendSeckillOrder(message);
```

这段代码的意义：

- Lua 负责 Redis 侧原子判断
- Java 侧只在资格通过后发消息
- 用户线程到这里就结束，不再同步落库

#### Kafka 消费落库核心代码

```java
RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
boolean isLock = redisLock.tryLock();
if (!isLock) {
    return;
}

int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
if (count > 0) {
    return;
}

boolean stockUpdated = seckillVoucherService.update()
        .setSql("stock = stock - 1")
        .eq("voucher_id", voucherId)
        .gt("stock", 0)
        .update();
```

这段代码的意义：

- 按 `userId` 加分布式锁，减少重复消费并发冲突
- 再查一次数据库是否已有订单
- 再扣数据库库存，做最终校验

#### 超时关单核心代码

```java
boolean closed = update()
        .eq("id", orderId)
        .eq("status", OrderStatusConstants.UNPAID)
        .set("status", OrderStatusConstants.CANCELLED)
        .set("update_time", LocalDateTime.now())
        .update();
if (!closed) {
    return false;
}

seckillVoucherService.update()
        .setSql("stock = stock + 1")
        .eq("voucher_id", order.getVoucherId())
        .update();
restoreRedisReservation(order.getVoucherId(), order.getUserId());
```

这段代码的意义：

- 用状态 CAS 只允许未支付订单被关单
- 关单成功后再回补 MySQL 和 Redis 库存

#### 支付并发控制核心代码

```java
return update()
        .eq("id", orderId)
        .eq("status", OrderStatusConstants.UNPAID)
        .set("status", OrderStatusConstants.PAID)
        .set("pay_time", LocalDateTime.now())
        .set("update_time", LocalDateTime.now())
        .update();
```

这段代码的意义：

- 支付和关单都去竞争 `status = UNPAID`
- 谁先更新成功，谁就抢到状态变更权

### 6.2 [LivPickKafkaProducer](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/producer/LivPickKafkaProducer.java)

这个类负责发送两类消息：

- 秒杀下单消息
- 缓存删除补偿消息

#### 核心代码

```java
kafkaTemplate.send(
                livPickProperties.getKafka().getSeckillOrderTopic(),
                String.valueOf(message.getOrderId()),
                JSONUtil.toJsonStr(message)
        )
        .get(5, TimeUnit.SECONDS);
```

这段代码的意义：

- 用 `orderId` 做 Kafka key
- 同步等待 5 秒，尽快确认消息是否真正发送成功
- 如果发送失败，业务侧就可以立即做 Redis 回滚

### 6.3 [SeckillOrderConsumer](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/consumer/SeckillOrderConsumer.java)

这个类负责消费秒杀下单消息。

#### 核心代码

```java
@KafkaListener(topics = "${livpick.kafka.seckill-order-topic}", groupId = "${spring.kafka.consumer.group-id}")
public void consume(String messageJson) {
    SeckillOrderMessage message = JSONUtil.toBean(messageJson, SeckillOrderMessage.class);
    try {
        voucherOrderService.createVoucherOrder(message);
    } catch (Exception e) {
        throw e;
    }
}
```

这段代码的意义：

- Consumer 不吞异常
- 落库失败时继续抛异常，让 Kafka 走重投
- 幂等交给业务层处理，而不是假设 MQ 一定只投递一次

### 6.4 [OrderTimeoutDelayQueueManager](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/delay/OrderTimeoutDelayQueueManager.java)

这个类是 Redisson 延迟队列的核心入口。

#### 初始化和投递核心代码

```java
blockingDeque = redissonClient.getBlockingDeque(livPickProperties.getOrder().getDelayQueueName());
delayedQueue = redissonClient.getDelayedQueue(blockingDeque);
consumerExecutor.submit(this::consume);

delayedQueue.offer(
        JSONUtil.toJsonStr(message),
        livPickProperties.getOrder().getTimeoutMinutes(),
        TimeUnit.MINUTES
);
```

这段代码的意义：

- `RBlockingDeque` 是真正业务线程消费的队列
- `RDelayedQueue` 是延迟层
- 到期后 Redisson 会把消息从延迟层搬到阻塞队列

#### 消费核心代码

```java
private void consume() {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            String messageJson = blockingDeque.take();
            OrderTimeoutMessage message = JSONUtil.toBean(messageJson, OrderTimeoutMessage.class);
            voucherOrderService.closeTimeoutOrder(message.getOrderId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("consume timeout order message failed", e);
        }
    }
}
```

这段代码的意义：

- `take()` 是阻塞式消费，不需要自己轮询
- 到期后消息会被立刻消费
- 真正的业务操作还是统一调用 `closeTimeoutOrder()`

### 6.5 [OrderTimeoutFallbackTask](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/task/OrderTimeoutFallbackTask.java)

这个类是延迟队列的兜底任务。

#### 核心代码

```java
@Scheduled(fixedDelayString = "${livpick.order.timeout-scan-interval-ms}")
public void scanTimeoutOrders() {
    voucherOrderService.scanAndCloseTimeoutOrders();
}
```

这段代码的意义：

- 用 `fixedDelay` 而不是 `fixedRate`
- 避免上一次扫描没结束，下一次扫描又叠上来
- 它不是主方案，只负责扫漏

### 6.6 [ShopServiceImpl](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/ShopServiceImpl.java)

这个类里有两条和面试强相关的逻辑：

- 布隆过滤器防穿透
- 更新数据库后删缓存，失败则 Kafka 补偿

#### 布隆过滤器查询核心代码

```java
Shop shop = cacheClient
        .queryWithBloomPassThrough(
                CACHE_SHOP_KEY, id, shopBloomFilterService::mightContain, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
```

这段代码的意义：

- 先过布隆过滤器
- 过滤器判断不存在就直接返回
- 判断可能存在才继续走缓存和数据库

#### 更新后删缓存失败补偿核心代码

```java
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
    livPickKafkaProducer.sendCacheDeleteRetry(message);
}
```

这段代码的意义：

- 先更新数据库
- 再删缓存
- 如果删缓存失败，转成异步补偿

### 6.7 [CacheDeleteRetryConsumer](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/mq/consumer/CacheDeleteRetryConsumer.java)

这个类负责缓存删除补偿重试。

#### 核心代码

```java
try {
    cacheClient.delete(message.getCacheKey());
} catch (Exception e) {
    int nextRetryCount = message.getRetryCount() + 1;
    if (nextRetryCount > livPickProperties.getCache().getDeleteRetryMaxAttempts()) {
        log.error("cache delete retry exhausted, key={}", message.getCacheKey(), e);
        return;
    }
    message.setRetryCount(nextRetryCount);
    livPickKafkaProducer.sendCacheDeleteRetry(message);
}
```

这段代码的意义：

- 删缓存失败就继续重试
- 但不是无限重试，而是有最大次数限制
- 超过次数就告警，由人工或后续兜底处理

### 6.8 [ShopBloomFilterService](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/ShopBloomFilterService.java)

这个类是布隆过滤器的核心实现。

#### 初始化核心代码

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

这段代码的意义：

- 通过 Redisson 拿到 `RBloomFilter`
- 初始化容量和误判率
- 如果发现过滤器为空，就从数据库全量重建

#### 重建核心代码

```java
shopBloomFilter.delete();
shopBloomFilter = redissonClient.getBloomFilter(livPickProperties.getBloom().getShopFilterName());
shopBloomFilter.tryInit(
        livPickProperties.getBloom().getExpectedInsertions(),
        livPickProperties.getBloom().getFalseProbability()
);
List<Shop> shops = shopMapper.selectList(null);
shops.forEach(shop -> shopBloomFilter.add(String.valueOf(shop.getId())));
```

这段代码的意义：

- 布隆过滤器不擅长删除，所以需要支持全量重建
- 重建时重新按配置初始化，再全量刷入店铺 ID

### 6.9 [CacheClient](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/CacheClient.java)

这个类体现了缓存穿透和缓存击穿的封装思路。

#### 布隆过滤器 + 缓存空值核心代码

```java
public <R, ID> R queryWithBloomPassThrough(
        String keyPrefix, ID id, Predicate<ID> bloomFilterPredicate, Class<R> type,
        Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    if (!bloomFilterPredicate.test(id)) {
        return null;
    }
    return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
}
```

这段代码的意义：

- 第一层先过布隆过滤器
- 第二层再走缓存空值的穿透防护
- 两层组合比单独用任意一种都更稳

#### 逻辑过期核心代码

```java
if(expireTime.isAfter(LocalDateTime.now())) {
    return r;
}
String lockKey = LOCK_SHOP_KEY + id;
boolean isLock = tryLock(lockKey);
if (isLock){
    CACHE_REBUILD_EXECUTOR.submit(() -> {
        try {
            R newR = dbFallback.apply(id);
            this.setWithLogicalExpire(key, newR, time, unit);
        } finally {
            unlock(lockKey);
        }
    });
}
return r;
```

这段代码的意义：

- 逻辑过期后不阻塞所有请求
- 只有抢到锁的线程负责重建缓存
- 其他线程先返回旧值，优先保证可用性

---

## 7. 指定拷打问题标准回答

下面这 7 个问题，都是你这套实现里很容易被追问的。

### 7.1 问题 2：既然 Kafka 会重复消费，为什么不用事务消息或本地消息表？

我当时有考虑过更重的一致性方案，比如事务消息或者本地消息表，但这个项目最后没有上这两种，主要是权衡实现成本和当前业务规模。

因为当前项目的核心目标是先把秒杀链路从同步改造成可抗高并发的异步架构，业务规模还没大到必须引入本地消息表或 Outbox 的程度。如果直接上事务消息或本地消息表，确实一致性会更强，但开发复杂度、维护成本和面试讲解成本也都会明显增加。

所以我最后采用的是一套更轻量的最终一致性方案：

- Redis 入口先做原子资格校验和预扣
- Producer 发送 Kafka 失败就立即回滚 Redis 预扣
- Consumer 侧用 Redisson 锁、查重和数据库唯一索引处理重复消费
- 异常场景通过补偿和回补逻辑收敛最终状态

也就是说，我这里不是忽略一致性，而是在当前项目阶段用“业务幂等 + 补偿兜底”替代更重的分布式事务方案。

### 7.2 问题 4：如果 Kafka 消息发送成功了，但 Consumer 落库失败了，你的 Redis 预扣库存怎么办？

这个场景要分两类看。

第一类是可重试失败，比如数据库瞬时异常、网络抖动。这种情况下我会让 Consumer 抛异常，不吞掉，让 Kafka 重新投递。因为 Redis 侧的预扣资格已经保留住了，所以只要重试成功，最终还是能和数据库状态对齐。

第二类是业务最终失败，比如数据库库存扣减失败或者订单最终没落成。这时候就要执行回补逻辑，把 Redis 里预扣的库存加回去，同时把用户的下单标记移除，避免资格一直被占住。

所以这条链路的核心不是“一次必须成功”，而是：

- 能重试的交给 Kafka 重试
- 不能重试或最终失败的走回补
- 最终靠幂等和补偿把 Redis 与 MySQL 状态重新收敛

### 7.3 问题 5：为什么 Redisson 延迟队列后面还要再加一个 SpringTask，是否重复？

不是重复，而是主链路和兜底链路的关系。

Redisson 延迟队列负责大多数正常情况，它的优势是订单到期后能准实时触发关单，不需要像数据库轮询那样等下一轮扫描。

但任何异步链路都可能出现异常，比如：

- 消费线程挂掉
- 服务重启时消息没及时处理
- Redis 或网络短时异常

如果只依赖延迟队列，一旦极端情况下漏掉某条消息，这笔订单可能会长时间不关单。  
所以我又加了一个低频的 SpringTask 扫描超时未支付订单，它不负责主流程，只负责扫漏。

所以面试里可以概括成一句话：  
“Redisson 延迟队列负责准实时处理，SpringTask 负责异常场景兜底，两者不是重复，而是互补。”

### 7.4 问题 6：Redisson 延迟队列能不能保证订单到期瞬间立刻关单？如果不能，误差从哪里来？

严格意义上不能保证绝对 0 延迟，因为这不是硬实时系统。

延迟主要可能来自几个地方：

- Redisson 需要把到期消息从延迟结构搬运到阻塞队列
- JVM 线程调度有时间片开销
- Redis 网络抖动、GC、CPU 抢占都会带来毫秒到秒级误差

但它和数据库定时轮询不一样，正常情况下是准实时的。因为消费线程本身是 `take()` 阻塞等待，到期后消息一搬过来就会立即消费。

真正会出现“可能再多等几十秒”的，一般是异常兜底链路，也就是延迟队列没正常处理时，才会由 SpringTask 在下一轮扫描里补回来。当前项目的兜底扫描间隔是 60 秒，所以异常情况下关单时效可能放宽到分钟级。

### 7.5 问题 8：删除缓存失败为什么选 Kafka 重试，而不是 canal 或延迟双删？

我这里主要是按实现复杂度、项目阶段和技术栈匹配度做的选择。

先说延迟双删。  
延迟双删确实能在一部分场景下降低脏数据窗口，但它本身依赖延迟时间选取得足够合适，而且对当前这个项目来说，更新链路本来就不复杂，我不太想再引入一套额外的时间窗口控制逻辑。

再说 canal。  
canal 的优点是解耦，可以统一监听 binlog 来更新缓存，但它要额外维护 canal 实例和 binlog 链路，实现、运维和排障成本都会更高。

而 Kafka 重试的优点是：

- 我项目里本来就已经用了 Kafka
- 实现直接，业务补偿逻辑清晰
- 重试次数、日志和后续扩展都比较容易自己控制

所以在当前这个项目阶段，我更看重链路清晰、成本可控和容易讲明白，所以最终选了 Kafka 重试。

### 7.6 问题 9：如果 Redisson 锁续期失败，或者业务线程卡死，会不会出现锁提前过期导致并发问题？

会有这个风险，所以不能把 Redisson 锁当成唯一兜底。

Redisson 在没有显式传 `leaseTime` 的情况下会启用 watchdog 自动续期，正常情况下只要业务线程还活着、客户端和 Redis 连接正常，锁就会持续续期。

但如果出现这些异常：

- JVM 长时间 Stop-The-World
- 业务线程卡死
- 客户端和 Redis 连接异常
- 服务实例直接宕机

那确实可能出现锁没有及时续期，最终提前过期，被别的线程重新获取。

所以这个项目里我的思路不是“绝对相信锁”，而是把 Redisson 锁放在第一层减冲突，再用数据库唯一索引做最终兜底。  
也就是说，即使极端情况下锁失效，数据库层仍然能防住一人多单，只是冲突会更多，但不会破坏最终正确性。

### 7.7 问题 10：你的 Kafka 在这个项目里用单 broker 就够了吗？如果后续压测或生产，应该怎么升级？

当前这个项目开发联调阶段，用单 broker 就够了，而且我采用的是本地单机 KRaft 模式。

这样做的原因很简单：

- 当前目标是先把异步落库和补偿链路跑通
- 单 broker 最容易部署、排障和观察日志
- 对开发和本地演示来说已经足够

但如果后续要做压测或生产，就不能继续停留在单 broker。

我的升级思路会是：

- 压测阶段：如果只是功能压测，本地单 broker 还可以继续用；如果要看更真实的吞吐、网络和消费并发，就迁移到云上 Kafka
- 生产阶段：采用 KRaft 集群，推荐 `3 Controller + 3 Broker`
- Topic 副本数至少配到 `3`
- `min.insync.replicas` 配到 `2`
- 不再使用 ZooKeeper，也不再使用单机 combined mode

所以统一口径就是：  
“开发联调用本地单 broker KRaft，后续压测和生产再升级到云上或集群部署。”

---

## 8. 你最后至少要讲顺的 3 句话

### 8.1 秒杀链路

我先用 Redis + Lua 在入口把库存校验和一人一单原子做掉，再通过 Kafka 把同步落库改造成异步削峰，消费端再用 Redisson 锁和数据库唯一索引保证幂等和最终正确性。

### 8.2 超时关单链路

订单创建成功后我会投递 Redisson 延迟队列，到期后自动尝试关单并回补库存，同时再加 SpringTask 低频扫描做兜底，避免异步链路异常导致订单长期不关闭。

### 8.3 一致性链路

这个项目没有上分布式事务，而是通过 Redis 原子预扣、Kafka 异步落库、业务幂等、缓存补偿重试和数据库唯一索引，把整个系统做成最终一致性方案。
