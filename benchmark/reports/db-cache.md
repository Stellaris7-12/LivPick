# DB-Cache 专题报告

## 1. 摘要

本报告是当前 benchmark 目录的核心专题报告，统一覆盖：

- `db-cache standard`
- `db-cache flash-sale`

对应套件入口：

- `benchmark/suites/db-cache/standard/`
- `benchmark/suites/db-cache/flash-sale/`

对应权威结果目录：

- `target/benchmark/jmeter/db-cache/run-20260529-114434`
- `target/benchmark/jmeter/flash-sale/db-cache/run-20260530-110438`

核心结论：

1. 在常规成功写路径场景中，`db-cache` 的成功下单吞吐仍主要受 MySQL 热点库存行限制，基准吞吐大致稳定在 `~200 req/s`。
2. 在商户详情读缓存相关场景中，Redis 收益非常明显，`cache-hit-200 / cache-penetration-200 / cache-breakdown-200` 都能达到 `6.5k ~ 8.9k req/s`。
3. 在更符合真实秒杀特征的 `flash-sale` 场景中，Redis 的主要价值不是无限抬高成功写吞吐，而是快速过滤大量失败请求、降低数据库锁竞争并提升整体入口吞吐。

## 2. 环境与启动参数

- JDK：`11`
- MySQL：`127.0.0.1:3306 / livpick_db_cache`
- Redis：Docker 容器 `livpick-redis-db-cache`，端口 `6380`
- JMeter：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`
- 应用端口：`8081`

应用启动参数：

```text
--server.port=8081
--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_db_cache?useSSL=false&serverTimezone=UTC
--spring.datasource.username=root
--spring.datasource.password=heyunhui2856
--spring.redis.host=127.0.0.1
--spring.redis.port=6380
--app.benchmark.enabled=true
--app.benchmark.skip-login-check=true
--app.seckill.consumer.enabled=false
```

含义：

- `app.benchmark.enabled=true`：开启 `/benchmark/**` 管理接口
- `app.benchmark.skip-login-check=true`：允许 JMeter 通过 `X-Benchmark-User-Id` 注入身份
- `app.seckill.consumer.enabled=false`：关闭旧的 Stream 消费线程

## 3. 执行步骤

### 3.1 数据库准备

初始化独立数据库：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 < benchmark\suites\db-cache\standard\sql\create_database.sql
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 livpick_db_cache < src\main\resources\db\hmdp2.sql
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 livpick_db_cache < benchmark\suites\db-cache\standard\sql\patch_schema.sql
```

### 3.2 Redis 准备

确认 Redis 已启动：

```powershell
docker ps --filter "name=livpick-redis-db-cache"
docker exec livpick-redis-db-cache redis-cli PING
```

### 3.3 standard 套件执行

单场景验证：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\suites\db-cache\standard\run-jmeter-benchmark.ps1 `
  -Scenario baseline-50 `
  -AppBaseUrl http://127.0.0.1:8081
```

全量执行：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\suites\db-cache\standard\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

### 3.4 flash-sale 套件执行

单场景验证：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\suites\db-cache\flash-sale\run-jmeter-benchmark.ps1 `
  -Scenario flash-sustain-5k-100 `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

全量执行：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\suites\db-cache\flash-sale\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

### 3.5 运行中自动处理

runner 会自动完成：

- 重置 `tb_seckill_voucher` 和 `tb_voucher_order`
- 清理 Redis 秒杀库存键、下单集合、商户缓存相关键
- 从 MySQL 重新写回 `seckill:stock:{voucherId}`
- 重置 benchmark 计数器
- 采集 `runtime-monitor.csv`、`gc-monitor.csv`、`redis-monitor.csv`
- 导出 `statement-digest.txt`、`lock-diagnostics.txt`、`innodb-status.txt`
- 生成 `benchmark-metrics-before/after/delta.json`

## 4. standard 场景结果

### 4.1 秒杀主链路

| 场景 | Samples | Success | QPS | Avg RT(ms) | P95(ms) | P99(ms) | Max(ms) | Error |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `baseline-50` | 12264 | 12264 | 205.17 | 234.10 | 289 | 383 | 694 | 0% |
| `baseline-100` | 11927 | 11927 | 199.33 | 482.58 | 616 | 985 | 1510 | 0% |
| `baseline-200` | 12685 | 12685 | 212.65 | 910.58 | 1163 | 1707 | 2534 | 0% |
| `baseline-500` | 12384 | 12384 | 207.56 | 2344.12 | 2773 | 3295 | 3880 | 0% |

结论：

- 从 `50` 提高到 `500` 并发，QPS 没有成比例上升，始终停留在 `~200 req/s`
- 并发越高，RT 明显恶化，说明系统很早就进入热点竞争和等待区

### 4.2 超卖校验

| 场景 | Samples | Success | Failed | QPS | Avg RT(ms) | Final Stock | Final Orders |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `oversell-100` | 50000 | 3000 | 47000 | 1249.59 | 74.79 | 0 | 3000 |

结论：

- 无超卖
- `3000` 库存全部售完
- 之后 `47000` 次请求全部被正确拒绝为 `库存不足`

### 4.3 一人一单

| 场景 | Samples | Success | Failed | QPS | Avg RT(ms) | Final Orders | Duplicate Users |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `one-user-one-order-100` | 5000 | 1000 | 4000 | 746.71 | 89.02 | 1000 | 0 |

结论：

- 一人一单校验正确
- 重复请求都被正确拒绝
- 数据库最终没有重复用户订单

### 4.4 商户详情缓存相关场景

| 场景 | Samples | QPS | Avg RT(ms) | 关键指标 |
| --- | ---: | ---: | ---: | --- |
| `cache-hit-200` | 393940 | 6583.55 | 28.42 | `CacheHit=393940` |
| `cache-penetration-200` | 534116 | 8919.33 | 20.01 | `NullHit=534106, DbFallback=10` |
| `cache-breakdown-200` | 502882 | 8401.67 | 22.11 | `DbFallback=1, RebuildSuccess=1` |

结论：

- 读缓存收益非常明显
- 空值缓存有效抑制穿透
- 热点 Key 逻辑过期只触发一次真实回源和重建

## 5. flash-sale 场景结果

### 5.1 场景矩阵

| 场景名 | 类型 | 用户池 | 库存 | 线程数 | 持续时间 |
| --- | --- | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 爆发 | 5000 | 100 | 1000 | 10s |
| `flash-burst-5k-500` | 爆发 | 5000 | 500 | 1000 | 10s |
| `flash-sustain-5k-100` | 持续 | 5000 | 100 | 500 | 60s |
| `flash-sustain-5k-500` | 持续 | 5000 | 500 | 500 | 60s |

### 5.2 结果总表

| 场景 | Samples | Success | Failed | QPS | Avg RT(ms) | P95(ms) | P99(ms) | Max(ms) | Error | Final Stock | Final Orders |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 7639 | 100 | 7539 | 782.44 | 551.52 | 902 | 937 | 1002 | 98.69% | 0 | 100 |
| `flash-burst-5k-500` | 3977 | 500 | 3477 | 411.23 | 872.92 | 2314 | 2652 | 3747 | 87.43% | 0 | 500 |
| `flash-sustain-5k-100` | 50000 | 100 | 49900 | 1190.36 | 374.20 | 568 | 634 | 843 | 99.80% | 0 | 100 |
| `flash-sustain-5k-500` | 50000 | 500 | 49500 | 1184.95 | 363.64 | 584 | 681 | 787 | 99.00% | 0 | 500 |

库存一致性：

- 四个场景都没有超卖
- 成功订单数与库存完全一致
- 库存耗尽后请求被正确拒绝

### 5.3 失败类型

持续场景：

- `flash-sustain-5k-100`：`49900` 次失败全部为 `库存不足`
- `flash-sustain-5k-500`：`49500` 次失败全部为 `库存不足`

爆发场景：

- `flash-burst-5k-100`：`库存不足=7267`，`HttpHostConnectException=272`
- `flash-burst-5k-500`：`库存不足=3257`，`HttpHostConnectException=220`

解释：

- 持续场景最能体现 Redis 的入口过滤价值
- `1000` 线程短爆发场景暴露的是单机接入极限，除了业务失败还出现了系统级连接失败

## 6. 合并分析

### 6.1 普通写路径为什么和 mysql-only 差不多

`baseline-500` 中：

- `QPS = 207.56 req/s`
- `Avg RT = 2344.12 ms`
- `RowLockWaitsDelta = 12265`
- `RowLockTimeDeltaMs = 507923`

MySQL 最重的 SQL 仍然是：

```text
UPDATE tb_seckill_voucher SET stock = stock - ? WHERE (voucher_id = ? AND stock > ?)
```

而订单插入 SQL 的平均开销只有 `~0.30ms`。

结论：

- 真正拖慢成功写吞吐的不是 Redis，也不是订单插入
- 核心瓶颈仍是 `tb_seckill_voucher` 单行库存更新的高并发竞争

### 6.2 Redis 在读缓存场景改善了什么

`cache-hit-200 / cache-penetration-200 / cache-breakdown-200` 已经证明：

- 缓存命中后，MySQL 基本退出热点路径
- 空值缓存能把穿透请求压在 Redis 层
- 逻辑过期能把热点 Key 的回源收敛到极少次数

因此：

- 商户详情缓存相关能力是有效的
- 缓存空值和逻辑过期都已经在实测里体现出价值

### 6.3 Redis 在高反差秒杀入口承载了什么

`flash-sustain-5k-100` 与 `flash-sustain-5k-500`：

- QPS 都稳定在 `~1.18k req/s`
- `RedisHitsDelta` 分别约 `47805`、`46650`
- `RowLockWaitsDelta` 只有 `99`、`321`
- `RowLockTimeDeltaMs` 只有 `4507`、`8606`

与 `baseline-500` 对比可见：

- Redis 没有让成功写路径脱离数据库
- 但它显著减少了无效请求持续冲击数据库热点行
- 这就是 `db-cache` 在真实秒杀高反差场景里的核心收益

补充说明：

- 本次 `flash-sale` 压测的是秒杀写路径，不是商户详情读缓存路径
- 因此 `benchmark-metrics-delta.json` 中的 `cacheHit / nullHit / cacheMiss` 基本为 `0` 是正常现象
- 在 `flash-sale` 中更值得关注的是 `RedisHitsDelta`、`Redis Ops/s`、业务失败占比和 MySQL 锁等待下降

### 6.4 为什么 1000 线程短爆发出现系统失败

持续场景中：

- `Max App CPU ≈ 22.88% ~ 24.81%`
- `Max System CPU ≈ 66.10% ~ 82.51%`

而短爆发场景出现了 `HttpHostConnectException`。

解释：

- `500` 线程持续压测仍处于更可信的业务分析区间
- `1000` 线程短爆发更适合暴露“JMeter + 应用 + MySQL + Redis 同机”时的接入极限

## 7. 最终结论

`db-cache` 当前的收益边界可以概括为：

1. 读流量：Redis 收益非常显著
2. 成功写路径：仍受 MySQL 单行库存更新限制
3. 高反差秒杀入口：Redis 对失败请求过滤和数据库减压很有价值

所以：

- 如果只看普通成功写场景，`db-cache` 和 `mysql-only` 结果接近是正常的
- 如果看更符合真实秒杀特征的“库存极少、需求极大”场景，Redis 的价值会明显体现在整体入口吞吐和数据库减压上
- 如果后续还要显著提升“成功订单”的吞吐能力，下一阶段仍然需要引入 MQ 或其他异步落库方案
