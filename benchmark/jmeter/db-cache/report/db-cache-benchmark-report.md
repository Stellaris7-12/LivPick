# DB-Cache 压测报告

## 1. 结论摘要

本轮对 `MySQL + Redis` 秒杀架构完成了秒杀主链路与缓存链路压测，结果目录为：

```text
target/benchmark/jmeter/db-cache/run-20260529-114434
```

核心结论：

- 秒杀主链路在 `50 -> 500` 并发下，吞吐量基本稳定在 `~200 req/s`
- 并发继续提高时，吞吐量没有继续上升，但 RT 明显线性恶化
- 秒杀阶段的主瓶颈不在 Redis，也不在 CPU，而在 `tb_seckill_voucher` 单行库存更新的数据库竞争
- 缓存命中场景吞吐量达到 `6.5k req/s`
- 缓存穿透场景吞吐量达到 `8.9k req/s`，空值缓存生效后几乎不再回源 DB
- 热点 Key 逻辑过期场景吞吐量达到 `8.4k req/s`，实际只发生 `1` 次 DB 重建，其他过期请求基本都返回旧值

## 2. 测试环境

- 应用：`feature/seckill-db-cache`
- JDK：`11`
- MySQL：`127.0.0.1:3306 / livpick_db_cache`
- Redis：Docker 容器 `livpick-redis-db-cache`，端口 `6380`
- JMeter：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`
- 应用启动参数：

```text
--server.port=8081
--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_db_cache?useSSL=false&serverTimezone=UTC
--spring.redis.host=127.0.0.1
--spring.redis.port=6380
--app.benchmark.enabled=true
--app.benchmark.skip-login-check=true
--app.seckill.consumer.enabled=false
```

## 3. 场景说明

秒杀场景：

- `baseline-50`
- `baseline-100`
- `baseline-200`
- `baseline-500`
- `oversell-100`
- `one-user-one-order-100`

缓存场景：

- `cache-hit-200`
- `cache-penetration-200`
- `cache-breakdown-200`

所有正式场景均开启运行时监控。

## 4. 秒杀场景结果

### 4.1 基线吞吐与 RT

| 场景 | Samples | Success | QPS | Avg RT(ms) | P95(ms) | P99(ms) | Max(ms) | Error |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline-50 | 12264 | 12264 | 205.17 | 234.10 | 289 | 383 | 694 | 0% |
| baseline-100 | 11927 | 11927 | 199.33 | 482.58 | 616 | 985 | 1510 | 0% |
| baseline-200 | 12685 | 12685 | 212.65 | 910.58 | 1163 | 1707 | 2534 | 0% |
| baseline-500 | 12384 | 12384 | 207.56 | 2344.12 | 2773 | 3295 | 3880 | 0% |

观察：

- 从 `50` 并发提升到 `500` 并发，吞吐量没有成比例提升，始终停留在 `~200 req/s`
- `baseline-200` 达到本轮最高吞吐 `212.65 req/s`
- `baseline-500` 相比 `baseline-50`，平均 RT 从 `234ms` 增长到 `2344ms`
- 说明系统在较早阶段就进入了串行化竞争区，提高线程数只会把等待时间堆高

### 4.2 超卖校验

| 场景 | Samples | Success | Failed | QPS | Avg RT(ms) | Final Stock | Final Orders |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| oversell-100 | 50000 | 3000 | 47000 | 1249.59 | 74.79 | 0 | 3000 |

结论：

- 无超卖
- `3000` 张库存全部卖完
- 之后的 `47000` 次请求全部被正确拒绝为 `库存不足`

### 4.3 一人一单校验

| 场景 | Samples | Success | Failed | QPS | Avg RT(ms) | Final Orders | Duplicate Users |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| one-user-one-order-100 | 5000 | 1000 | 4000 | 746.71 | 89.02 | 1000 | 0 |

结论：

- 一人一单校验正确
- `1000` 个用户下单成功
- 剩余 `4000` 次重复请求被正确拒绝
- 数据库最终没有重复用户订单

## 5. 缓存场景结果

### 5.1 商户缓存命中

| 场景 | Samples | QPS | Avg RT(ms) | P95(ms) | CacheHit | CacheMiss | DbFallback |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| cache-hit-200 | 393940 | 6583.55 | 28.42 | 40 | 393940 | 0 | 0 |

结论：

- 预热后命中率为 `100%`
- 整个场景没有回源 DB
- MySQL 几乎空闲，吞吐量主要受应用层 HTTP/序列化与 Redis 往返影响

### 5.2 缓存穿透

| 场景 | Samples | QPS | Avg RT(ms) | NullHit | CacheMiss | DbFallback |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cache-penetration-200 | 534116 | 8919.33 | 20.01 | 534106 | 10 | 10 |

结论：

- 只有最前面的 `10` 次请求回源 DB
- 随后 `534106` 次请求命中空值缓存
- 空值缓存生效后，穿透请求不再打 MySQL

### 5.3 热点 Key 逻辑过期

| 场景 | Samples | QPS | Avg RT(ms) | CacheHit | StaleHit | DbFallback | RebuildScheduled | RebuildSuccess | RebuildLockHit | RebuildLockMiss |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| cache-breakdown-200 | 502882 | 8401.67 | 22.11 | 502868 | 14 | 1 | 1 | 1 | 1 | 13 |

结论：

- 热点 Key 强制逻辑过期后，只有 `1` 次请求真正回源 DB 并触发重建
- `14` 次请求命中过期值，直接返回旧数据
- 有 `13` 次并发请求因为拿不到重建锁而放弃重建，避免了缓存击穿时的 DB 风暴

## 6. 系统指标与瓶颈分析

### 6.1 秒杀主链路瓶颈

`baseline-500` 是最能暴露瓶颈的场景：

- QPS：`207.56 req/s`
- Avg RT：`2344.12 ms`
- Max App CPU：`11.31%`
- Max MySQL CPU：`6.06%`
- Max Redis CPU：`8.29%`
- Max Threads Running：`12`
- RowLockWaitsDelta：`12265`
- RowLockTimeDeltaMs：`507923`

MySQL `statement-digest` 显示最重 SQL 为：

```text
UPDATE tb_seckill_voucher SET stock = stock - ? WHERE (voucher_id = ? AND stock > ?)
COUNT_STAR = 63315
total_seconds = 2720.20
avg_ms = 42.963
```

而订单插入 SQL 开销很小：

```text
INSERT INTO tb_voucher_order (...) 
COUNT_STAR = 63315
total_seconds = 18.83
avg_ms = 0.297
```

结论：

- 真正拖慢秒杀吞吐的不是 Redis，也不是订单插入
- 核心瓶颈是 `tb_seckill_voucher` 单行库存更新造成的高并发竞争
- 数据库 CPU 没有打满，但行锁等待已经把 RT 明显拉高
- 这属于“锁竞争瓶颈”，不是“算力瓶颈”

### 6.2 Redis 在秒杀阶段的作用

`baseline-500` 的 Redis 指标：

- Max Redis CPU：`8.29%`
- Max Redis Memory：`9.42 MB`
- Max Redis Ops/s：`1292`

说明：

- Redis 预检和 Lua 原子扣减本身不是这一轮的主瓶颈
- Redis 负载相对可控
- 即使并发提高到 `500`，Redis 仍明显早于数据库之外的其他资源到达瓶颈

### 6.3 缓存阶段的瓶颈迁移

`cache-hit-200`：

- QPS：`6583.55 req/s`
- Max App CPU：`41.19%`
- Max MySQL CPU：`0.50%`
- Max Redis CPU：`17.60%`
- Max Redis Ops/s：`7539`

`cache-penetration-200`：

- QPS：`8919.33 req/s`
- Max App CPU：`51.06%`
- Max MySQL CPU：`0%`
- Max Redis CPU：`13.16%`
- Max Redis Ops/s：`9613`

说明：

- 当请求主要落在缓存层时，MySQL 几乎退出热点路径
- 吞吐能力提升到秒杀主链路的几十倍
- 此时主要约束转移到应用层 HTTP 处理、JSON 序列化和 Redis 网络往返

## 7. 结果解读

本轮 `db-cache` 架构的收益主要体现在两个方面：

1. Redis 在秒杀入口提前完成库存与一人一单预检，避免了所有请求直接冲向 MySQL
2. 商户缓存相关读场景吞吐量显著高于写竞争场景，缓存策略本身是有效的

但 `db-cache` 依然没有解决最核心的写热点问题：

- 所有成功下单最终仍要竞争同一行 `tb_seckill_voucher`
- 所以秒杀成功路径的吞吐很快被数据库锁竞争限制在 `~200 req/s`

因此，这一版架构的阶段性结论是：

- 读性能已经明显受益于 Redis
- 写性能仍然主要受限于 MySQL 单行库存扣减
- 后续如果要继续提升秒杀吞吐，必须进一步削弱成功路径上的数据库同步写竞争

## 8. 复现命令

完整运行命令：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

应用启动命令见：

- `benchmark/jmeter/db-cache/README.md`

## 9. 产物说明

本次报告使用的原始产物在：

```text
target/benchmark/jmeter/db-cache/run-20260529-114434/
```

建议优先查看：

- `aggregate-summary.csv`
- `baseline-500/statement-digest.txt`
- `baseline-500/lock-diagnostics.txt`
- `cache-hit-200/benchmark-metrics-delta.json`
- `cache-penetration-200/benchmark-metrics-delta.json`
- `cache-breakdown-200/benchmark-metrics-delta.json`
