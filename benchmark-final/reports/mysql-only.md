# MySQL-only 专题报告

## 1. 摘要

本专题统一覆盖：

- `mysql-only standard`
- `mysql-only flash-sale`

对应套件入口：

- `benchmark-final/suites/mysql-only/standard/`
- `benchmark-final/suites/mysql-only/flash-sale/`

当前权威结果目录：

- `target/benchmark/jmeter/run-20260527-000448`
- `target/benchmark/jmeter/run-20260527-113409`
- `target/benchmark/jmeter/run-20260527-114025`
- `target/benchmark/jmeter/run-20260527-114347`
- `target/benchmark/jmeter/flash-sale/mysql-only/run-20260531-193947`

当前阶段结论：

1. `mysql-only` 在常规成功写路径下的吞吐大致稳定在 `200 ~ 230 QPS`。
2. 在 flash-sale 高反差场景中，`mysql-only` 入口吞吐提升到约 `410 ~ 577 req/s`，但失败请求仍大量直达 MySQL。
3. flash-sale 下成功订单数与库存完全一致，没有超卖，但 `RowLockWaitsDelta` 仍维持在 `3k ~ 30k` 量级，说明热点库存行竞争没有被入口层过滤掉。
4. 与 `db-cache flash-sale` 相比，`mysql-only` 的主要短板不是成功下单数更低，而是失败请求对数据库的持续冲击更重。

## 2. 环境与入口

- JDK：`11`
- 数据库：`livpick_mysql_only`
- 压测接口：`POST /voucher-order/seckill/7`
- 身份注入：`X-Benchmark-User-Id`
- standard 执行脚本：`benchmark-final/suites/mysql-only/standard/run-jmeter-benchmark.ps1`
- flash-sale 执行脚本：`benchmark-final/suites/mysql-only/flash-sale/run-jmeter-benchmark.ps1`
- 监控脚本：`benchmark-final/suites/mysql-only/standard/collect-runtime-monitor.ps1`

应用启动示例：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --spring.datasource.username=root `
  --spring.datasource.password=heyunhui2856 `
  --app.benchmark.skip-login-check=true
```

## 3. 执行步骤

### 3.1 数据库准备

初始化独立数据库并补齐一人一单唯一索引：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 < src\main\resources\db\hmdp2_mysql_only_seckill.sql
```

### 3.2 standard 套件执行

单场景验证：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark-final\suites\mysql-only\standard\run-jmeter-benchmark.ps1 `
  -Scenario baseline-50
```

全量执行：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark-final\suites\mysql-only\standard\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring
```

### 3.3 flash-sale 套件执行

单场景冒烟：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark-final\suites\mysql-only\flash-sale\run-jmeter-benchmark.ps1 `
  -Scenario flash-sustain-5k-100 `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

正式全量：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark-final\suites\mysql-only\flash-sale\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

### 3.4 flash-sale runner 自动处理

runner 会自动完成：

- 重置 `tb_seckill_voucher` 与 `tb_voucher_order`
- 预热一次低并发 burst 场景
- 生成 `aggregate-summary.csv`、场景级 `summary.json`、JTL 与 dashboard
- 采集 `runtime-monitor.csv`、`gc-monitor.csv`
- 导出 `statement-digest.txt`、`lock-diagnostics.txt`、`innodb-status.txt`
- 执行库存与订单数 post-check

## 4. standard 场景结果

### 4.1 吞吐基线

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 最终库存 | 最终订单数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 12663 | 12663 | 0 | 211.72 | 226.50 | 281 | 316 | 590 | 0.00% | 37337 | 12663 |
| 100 | 13101 | 13101 | 0 | 219.02 | 438.59 | 546 | 648 | 840 | 0.00% | 36899 | 13101 |
| 200 | 11661 | 11661 | 0 | 195.08 | 990.25 | 1647 | 1998 | 3416 | 0.00% | 38339 | 11661 |
| 500 | 12817 | 12817 | 0 | 214.58 | 2265.56 | 3290 | 3355 | 4200 | 0.00% | 37183 | 12817 |

结论：

- 本机环境下，`100` 线程时吞吐最高，约 `219.02 QPS`
- `100 -> 200 -> 500` 后，吞吐没有继续稳定提升
- 但平均响应时间和尾延迟显著恶化

### 4.2 超卖校验

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终库存 | 最终订单数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 22806 | 3000 | 19806 | 381.31 | 251.52 | 467 | 521 | 616 | 86.85% | `Out of stock=19806` | 0 | 3000 | 通过 |

### 4.3 一人一单校验

场景数据：

- 唯一用户数：`1000`
- 每个用户重复 `5` 次
- 总请求：`5000`

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终订单数 | 重复用户数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 5000 | 1000 | 4000 | 257.55 | 341.39 | 462 | 484 | 826 | 80.00% | `Duplicate orders are not allowed=4000` | 1000 | 0 | 通过 |

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
| `flash-burst-5k-100` | 4249 | 100 | 4149 | 438.54 | 1167.50 | 1864 | 1881 | 2234 | 97.65% | 0 | 100 |
| `flash-burst-5k-500` | 3999 | 500 | 3499 | 410.70 | 1304.63 | 2064 | 2077 | 2605 | 87.50% | 0 | 500 |
| `flash-sustain-5k-100` | 34347 | 100 | 34247 | 577.44 | 804.97 | 1058 | 1168 | 1614 | 99.71% | 0 | 100 |
| `flash-sustain-5k-500` | 34105 | 500 | 33605 | 574.55 | 813.23 | 1053 | 1186 | 1711 | 98.53% | 0 | 500 |

库存一致性：

- 四个场景都没有超卖
- 成功订单数与库存完全一致
- 压测结束后数据库最终停留在 `stock=0`、`order_count=500`

### 5.3 失败类型

持续场景：

- `flash-sustain-5k-100`：`Out of stock=34225`，`HttpHostConnectException=22`
- `flash-sustain-5k-500`：`Out of stock=33578`，`HttpHostConnectException=27`

爆发场景：

- `flash-burst-5k-100`：`Out of stock=3832`，`HttpHostConnectException=317`
- `flash-burst-5k-500`：`Out of stock=3163`，`HttpHostConnectException=336`

解释：

- 四个场景的主要业务失败原因都集中在 `Out of stock`
- 两个 burst 场景出现了更明显的 `HttpHostConnectException`，说明 `1000` 线程短爆发不仅打满业务热点，也暴露了单机接入上限
- sustain 场景中的系统级失败显著少于 burst，但仍未被完全消除

## 6. 带监控压测结果

### 6.1 standard 监控结论

本轮瓶颈定位主要关注：

- `target/benchmark/jmeter/run-20260527-113409`
- `target/benchmark/jmeter/run-20260527-114025`
- `target/benchmark/jmeter/run-20260527-114347`

| 场景 | 样本数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | Max App CPU | Max MySQL CPU | Max System CPU | Min Free Mem(MB) | Max Threads Connected | Max Threads Running | Max Row Lock Waits | Max Data Lock Waits | Max Old Gen | YGC 增量 | FGC 增量 | GC 时间增量(s) | Row Lock Waits 增量 | Row Lock Time 增量(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `baseline-100` | 13114 | 219.29 | 438.59 | 496 | 571 | 1329 | 6.81% | 8.06% | 42.44% | 2577 | 11 | 11 | 8 | 36 | 69.67% | 28 | 0 | 0.056 | 13089 | 467018 |
| `baseline-200` | 13572 | 227.14 | 850.67 | 971 | 1656 | 2685 | 7.88% | 6.00% | 22.22% | 2519 | 11 | 12 | 8 | 36 | 74.88% | 30 | 0 | 0.095 | 13525 | 469224 |
| `baseline-500` | 13737 | 230.10 | 2109.61 | 2612 | 3061 | 3905 | 7.69% | 6.06% | 33.42% | 2399 | 11 | 12 | 8 | 36 | 77.72% | 32 | 0 | 0.102 | 13614 | 475740 |

### 6.2 flash-sale 监控结果

对应权威结果目录：

- `target/benchmark/jmeter/flash-sale/mysql-only/run-20260531-193947`

| 场景 | Samples | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | Max App CPU | Max MySQL CPU | Max System CPU | Min Free Mem(MB) | Max Threads Connected | Max Threads Running | Max Row Lock Waits | Max Data Lock Waits | Row Lock Waits 增量 | Row Lock Time 增量(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 4249 | 438.54 | 1167.50 | 1864 | 1881 | 2234 | 19.94% | 7.56% | 69.72% | 3536 | 11 | 2 | 0 | 0 | 3586 | 12419 |
| `flash-burst-5k-500` | 3999 | 410.70 | 1304.63 | 2064 | 2077 | 2605 | 0.00% | 6.62% | 91.36% | 3519 | 11 | 11 | 8 | 36 | 3367 | 27351 |
| `flash-sustain-5k-100` | 34347 | 577.44 | 804.97 | 1058 | 1168 | 1614 | 18.50% | 11.06% | 90.90% | 3244 | 11 | 5 | 2 | 3 | 30894 | 69858 |
| `flash-sustain-5k-500` | 34105 | 574.55 | 813.23 | 1053 | 1186 | 1711 | 18.56% | 11.81% | 84.39% | 3373 | 11 | 8 | 3 | 6 | 30748 | 75369 |

热点 SQL：

```text
UPDATE tb_seckill_voucher SET stock = stock - ? WHERE ( voucher_id = ? AND stock > ? )
```

从 `statement-digest.txt` 看：

- burst 100 场景该 SQL 平均约 `28.236ms`
- sustain 100 场景该 SQL 平均约 `24.091ms`
- sustain 500 场景该 SQL 平均约 `21.338ms`

而以下 SQL 的平均耗时都维持在亚毫秒量级：

- 查询秒杀券：约 `0.284 ~ 0.311ms`
- 查询一人一单：约 `0.264 ~ 0.295ms`
- 插入订单：约 `0.248 ~ 0.249ms`

## 7. 瓶颈分析

### 7.1 standard 已确认瓶颈

`mysql-only` 当前的核心瓶颈是：

- `tb_seckill_voucher` 热点库存行的 InnoDB 行锁竞争

可以基本排除：

- Java CPU 打满
- MySQL CPU 打满
- 系统内存不足
- Full GC 抖动

### 7.2 flash-sale 分析

#### 7.2.1 入口吞吐为什么高于 standard

- `flash-sustain-5k-100 / 500` 都稳定在 `~575 req/s`
- 明显高于 standard 成功写路径的 `~200 req/s`

原因不是成功下单能力变强，而是：

- 大量请求在库存耗尽后快速失败
- 失败响应比完整成功事务更短
- 因此入口总 QPS 被“失败请求”抬高

#### 7.2.2 为什么瓶颈仍然是 MySQL 热点库存行

虽然 flash-sale 入口吞吐更高，但：

- `RowLockWaitsDelta` 仍然达到 `3367 ~ 30894`
- `RowLockTimeDeltaMs` 仍然达到 `12419 ~ 75369`
- `statement-digest` 最重 SQL 依旧是库存扣减 `UPDATE`

说明：

- `mysql-only` 并没有在数据库之前挡住失败请求
- 大量失败流量仍然进入 MySQL 竞争同一条库存行

#### 7.2.3 burst 与 sustain 的差异

- burst 场景的 `HttpHostConnectException` 更明显
- sustain 场景的系统级失败更少，但行锁等待增量更高

说明：

- `1000` 线程短爆发更容易暴露单机接入峰值
- `500` 线程持续压测更适合观察数据库长期热点竞争

## 8. 与 DB-Cache 轻量对照

对照维度 1：整体入口吞吐

- `mysql-only flash-sustain` 约 `574 ~ 577 req/s`
- `db-cache flash-sustain` 约 `1184 ~ 1190 req/s`
- `db-cache` 在高反差场景下的入口吞吐约为 `mysql-only` 的两倍

对照维度 2：成功订单数与库存一致性

- 两者都没有超卖
- 成功订单数都与库存严格一致

对照维度 3：失败请求主要落点

- `mysql-only` 中失败请求大多在进入 MySQL 后才以 `Out of stock` 返回
- `db-cache` 中失败请求更多在 Redis 层被快速过滤

对照维度 4：MySQL 锁等待强度

- `mysql-only flash-sustain-5k-100`：`RowLockWaitsDelta=30894`
- `mysql-only flash-sustain-5k-500`：`RowLockWaitsDelta=30748`
- `db-cache flash-sustain-5k-100`：`RowLockWaitsDelta=99`
- `db-cache flash-sustain-5k-500`：`RowLockWaitsDelta=321`

轻量结论：

- `mysql-only` 和 `db-cache` 在“成功订单数”上都受库存上限约束
- 两者真正差异不在是否卖得更多，而在失败请求有没有提前被过滤
- `db-cache` 的核心收益是显著减少无效流量对 MySQL 热点行的冲击

## 9. 过程记录

### 2026-05-31

- 已确认当前工作分支为 `feature/seckill-db-only`
- 已确认默认 JDK 为 `21`，本项目压测需切换到 JDK `11`
- 已补齐 `benchmark-final/suites/mysql-only/flash-sale/` 套件：
  - `flash-burst-high-contrast.jmx`
  - `flash-sustain-high-contrast.jmx`
  - `run-jmeter-benchmark.ps1`
  - `sql/reset_stock_flash_100.sql`
  - `sql/reset_stock_flash_500.sql`
- 已统一文档入口从旧 `benchmark/` 路径迁移到 `benchmark-final/`
- 已使用 JDK `11` 完成 `mvn -DskipTests package`
- 已检查 `8081` 与 `18081` 监听状态，确认无旧分支应用占用后，使用 `8081` 启动本分支应用
- 已完成冒烟场景 `flash-sustain-5k-100`
- 冒烟结果目录：`target/benchmark/jmeter/flash-sale/mysql-only/run-20260531-193440`
- 冒烟后校验通过：`FinalStock=0`，`FinalOrders=100`
- 已完成正式全量压测：`target/benchmark/jmeter/flash-sale/mysql-only/run-20260531-193947`
