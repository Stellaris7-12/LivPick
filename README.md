# LivPick - `feature/seckill-db-cache`

## 1. 本分支目的

本分支聚焦于秒杀系统的 `db-cache` 架构验证，也就是：

- 以 `MySQL + Redis` 作为当前目标架构
- 为后续与 `db-only`、`db-cache-mq` 做横向压测对比做准备
- 优先修正缓存模块中“空值缓存”和“逻辑过期”不能同时工作的设计问题
- 给优惠券读取链路补上 Redis 缓存
- 在消息队列模块彻底删除前，先把其对本分支测试与压测的干扰隔离掉

当前分支名：

- `feature/seckill-db-cache`

## 2. 当前分支相对主线的重点改动

### 2.1 缓存模块改动

本分支对缓存模块做了两类核心调整。

#### 商户详情缓存修正

之前 `ShopServiceImpl.queryById()` 默认只走空值缓存方案，逻辑过期方案虽然存在，但和空值缓存使用了不同的 Redis 值结构，不能安全共存。

本分支已经统一为单一包装结构：

- `present`
- `logicalExpireTime`
- `data`

现在商户缓存支持同时处理：

- 非法 `shopId` 的空值缓存，防止缓存穿透
- 热点 `shopId` 的逻辑过期与异步重建，防止缓存击穿

对应实现位置：

- [src/main/java/com/livepick/utils/CacheClient.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/CacheClient.java:1)
- [src/main/java/com/livepick/utils/RedisData.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/RedisData.java:1)
- [src/main/java/com/livepick/service/impl/ShopServiceImpl.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/ShopServiceImpl.java:1)

#### 优惠券列表缓存新增

当前前端页面虽然会展示商户优惠券，但后端并不是把优惠券塞进 `shop` 缓存，而是通过独立接口读取：

- `GET /voucher/list/{shopId}`

因此本分支没有把优惠券并进 `cache:shop:{id}`，而是给优惠券列表增加了独立 Redis key：

- `cache:voucher:list:{shopId}`

读取逻辑支持：

- 首次查询回源 MySQL
- 逻辑过期后返回旧值并异步重建
- 对“有店铺但无优惠券”的情况缓存空列表

写路径失效也已经补齐：

- 新增普通券后删除对应 `shopId` 的优惠券列表缓存
- 新增秒杀券后删除对应 `shopId` 的优惠券列表缓存

对应实现位置：

- [src/main/java/com/livepick/utils/RedisConstants.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/utils/RedisConstants.java:1)
- [src/main/java/com/livepick/service/impl/VoucherServiceImpl.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/VoucherServiceImpl.java:1)
- [src/main/java/com/livepick/controller/VoucherController.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/controller/VoucherController.java:1)
- [src/main/java/com/livepick/service/IVoucherService.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/IVoucherService.java:1)

### 2.2 消息队列模块调整

本分支的目标方向是：

- 不再依赖 `Redis Stream` 完成 `db-cache` 方案测试与压测
- 后续把秒杀主链路逐步收敛到 `MySQL + Redis`

但请注意，**当前仓库里消息队列模块还没有彻底删除**。

目前实际完成的是一层“过渡隔离”：

- 保留原有 `Redis Stream` 异步消费代码
- 新增配置项 `app.seckill.consumer.enabled`
- 在测试和 `db-cache` 运行时可以显式关闭后台 Stream 消费线程
- 避免因为没有创建 `stream.orders` 消费组而在启动时持续报 `NOGROUP`

这意味着当前状态是：

- `Redis Stream` 代码还在
- 但已经可以在本分支环境中安全禁用
- 为后续彻底移除消息队列模块铺路

对应实现位置：

- [src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java:1)

## 3. Redis 与数据库本地环境

### 3.1 Redis

为了避免污染你在其它分支里使用的中间件容器，本分支使用独立的 Redis 容器：

- 容器名：`livpick-redis-db-cache`
- 默认端口：`6380`
- 独立数据卷：`livpick-redis-db-cache-data`

启动文件：

- [docker-compose.redis.yml](/C:/Users/heyunhui/IdeaProjects/LivPick/docker-compose.redis.yml:1)

启动命令：

```powershell
$env:REDIS_PORT = "6380"
docker compose -f docker-compose.redis.yml up -d
```

如果需要密码：

```powershell
$env:REDIS_PORT = "6380"
$env:REDIS_PASSWORD = "你的Redis密码"
docker compose -f docker-compose.redis.yml up -d
```

应用侧建议使用：

- `REDIS_HOST=127.0.0.1`
- `REDIS_PORT=6380`
- `REDIS_PASSWORD=`

另外，本分支已将 `RedissonConfig` 改为读取 Spring Redis 配置，不再写死 Redis 地址与密码。

### 3.2 MySQL

本分支不建议直接复用：

- `hmdp`：主开发库
- `livpick_mysql_only`：`db-only` 压测库

建议使用独立库：

- `livpick_db_cache`

这样便于：

- 与 `db-only` 基线隔离
- 避免压测与日常开发数据混淆
- 便于后续继续扩展 `db-cache-mq` 对比方案

初始化文件：

- [benchmark/jmeter/db-cache/sql/create_database.sql](/C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/db-cache/sql/create_database.sql:1)
- [src/main/resources/db/hmdp2.sql](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/resources/db/hmdp2.sql:1)
- [benchmark/jmeter/db-cache/sql/patch_schema.sql](/C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/db-cache/sql/patch_schema.sql:1)

当前 `application.yaml` 已支持通过环境变量切换数据库名：

- `DB_NAME`

默认值仍是：

- `hmdp`

对应配置位置：

- [src/main/resources/application.yaml](/C:/Users/heyunhui/IdeaProjects/LivPick/src/main/resources/application.yaml:1)

## 4. db-cache 环境准备文件

为后续 `MySQL + Redis` 压测，本分支已新增：

- [benchmark/jmeter/db-cache/README.md](/C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/db-cache/README.md:1)
- [benchmark/jmeter/db-cache/sql/reset_stock_large.sql](/C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/db-cache/sql/reset_stock_large.sql:1)
- [benchmark/jmeter/db-cache/sql/reset_stock_small.sql](/C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/db-cache/sql/reset_stock_small.sql:1)
- [benchmark/jmeter/db-cache/sql/check_results.sql](/C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/db-cache/sql/check_results.sql:1)

这些文件的口径与 `mysql-only` 保持一致，目标是后续方便横向比较：

- 吞吐量（QPS）
- 平均响应时间 / 尾延迟
- 一人一单正确性
- 超卖正确性
- 缓存命中与缓存重建相关指标

## 5. 当前测试结果

针对这次缓存优化，本分支已经完成两层验证。

### 5.1 单元测试

新增缓存测试文件：

- [src/test/java/com/livepick/utils/CacheClientTest.java](/C:/Users/heyunhui/IdeaProjects/LivPick/src/test/java/com/livepick/utils/CacheClientTest.java:1)

覆盖了以下场景：

- 商户空值缓存
- 商户缓存首次回源
- 商户热点 key 过期后异步重建
- 优惠券空列表缓存
- 优惠券列表逻辑过期后异步重建

### 5.2 全量测试

在以下环境下已跑通 Maven 全量测试：

- `JDK 11`
- `DB_NAME=livpick_db_cache`
- `REDIS_PORT=6380`
- `app.seckill.consumer.enabled=false`

命令示例：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11'
$env:Path='C:\Program Files\Java\jdk-11\bin;' + $env:Path
$env:DB_HOST='127.0.0.1'
$env:DB_PORT='3306'
$env:DB_NAME='livpick_db_cache'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='你的MySQL密码'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='6380'
$env:REDIS_PASSWORD=''
mvn "-Dmaven.repo.local=C:\Users\heyunhui\.m2\repository" "-Dapp.seckill.consumer.enabled=false" test
```

本次结果：

- `Tests run: 11`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`

## 6. 当前状态说明

本分支现在处于一个适合继续推进 `db-cache` 压测的状态：

- 缓存模块已完成本轮必要修正
- 商户详情与优惠券列表都已接入可工作的 Redis 缓存
- 本地 Redis 与本地压测数据库已具备隔离方案
- 全量测试已通过

但消息队列模块仍是“过渡态”：

- 已支持通过配置关闭
- 尚未从代码层面彻底删除 `Redis Stream`

如果下一步继续推进，本分支后续最自然的工作是：

1. 彻底移除 `Redis Stream` 下单链路
2. 收敛为真正的 `MySQL + Redis` 秒杀主链路
3. 补全 `db-cache` 压测脚本与指标采集
4. 与 `db-only` 基线做正式横向对比
