# MySQL-only 专题报告

## 1. 摘要

本专题保留当前已经完成的 `mysql-only standard` 压测成果。

当前状态：

- 已完成 standard 场景压测与瓶颈分析
- 已形成正式结论
- 暂未补充 `flash-sale`

套件入口：

- `benchmark/suites/mysql-only/standard/`

当前权威结果目录：

- `target/benchmark/jmeter/run-20260527-000448`
- `target/benchmark/jmeter/run-20260527-113409`
- `target/benchmark/jmeter/run-20260527-114025`
- `target/benchmark/jmeter/run-20260527-114347`

核心结论：

1. `mysql-only` 基准吞吐大致稳定在 `200 ~ 230 QPS`。
2. 并发从 `100` 拉高到 `200`、`500` 后，QPS 基本封顶，但 RT 明显恶化。
3. 主瓶颈是 `tb_seckill_voucher` 热点库存行的 InnoDB 行锁竞争。
4. CPU、内存和 GC 都不是当前主瓶颈。

## 2. 环境与入口

- JDK：`11`
- 数据库：`livpick_mysql_only`
- 压测接口：`POST /voucher-order/seckill/7`
- 身份注入：`X-Benchmark-User-Id`
- 执行脚本：`benchmark/suites/mysql-only/standard/run-jmeter-benchmark.ps1`
- 监控脚本：`benchmark/suites/mysql-only/standard/collect-runtime-monitor.ps1`

应用启动示例：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

## 3. 已完成场景

- `baseline-50`
- `baseline-100`
- `baseline-200`
- `baseline-500`
- `oversell-100`
- `one-user-one-order-100`

## 4. 正式结果

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

结论：

- 无超卖
- 最终库存为 `0`
- 最终订单数为 `3000`

### 4.3 一人一单校验

场景数据：

- 唯一用户数：`1000`
- 每个用户重复 `5` 次
- 总请求：`5000`

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终订单数 | 重复用户数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 5000 | 1000 | 4000 | 257.55 | 341.39 | 462 | 484 | 826 | 80.00% | `Duplicate orders are not allowed=4000` | 1000 | 0 | 通过 |

结论：

- 最终订单数为 `1000`
- 重复下单用户数为 `0`

## 5. 带监控压测结果

本轮瓶颈定位主要关注：

- `target/benchmark/jmeter/run-20260527-113409`
- `target/benchmark/jmeter/run-20260527-114025`
- `target/benchmark/jmeter/run-20260527-114347`

| 场景 | 样本数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | Max App CPU | Max MySQL CPU | Max System CPU | Min Free Mem(MB) | Max Threads Connected | Max Threads Running | Max Row Lock Waits | Max Data Lock Waits | Max Old Gen | YGC 增量 | FGC 增量 | GC 时间增量(s) | Row Lock Waits 增量 | Row Lock Time 增量(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `baseline-100` | 13114 | 219.29 | 438.59 | 496 | 571 | 1329 | 6.81% | 8.06% | 42.44% | 2577 | 11 | 11 | 8 | 36 | 69.67% | 28 | 0 | 0.056 | 13089 | 467018 |
| `baseline-200` | 13572 | 227.14 | 850.67 | 971 | 1656 | 2685 | 7.88% | 6.00% | 22.22% | 2519 | 11 | 12 | 8 | 36 | 74.88% | 30 | 0 | 0.095 | 13525 | 469224 |
| `baseline-500` | 13737 | 230.10 | 2109.61 | 2612 | 3061 | 3905 | 7.69% | 6.06% | 33.42% | 2399 | 11 | 12 | 8 | 36 | 77.72% | 32 | 0 | 0.102 | 13614 | 475740 |

## 6. 瓶颈判断

### 6.1 现象

从 `100 -> 200 -> 500` 线程：

- QPS 只从 `219.29` 增长到 `230.10`
- Avg 从 `438.59ms` 增长到 `2109.61ms`
- P99 从 `571ms` 增长到 `3061ms`

说明系统在 `100` 线程附近已经接近吞吐平台期，后续增加线程主要带来等待和排队，而不是有效吞吐增长。

### 6.2 排除 CPU / 内存 / GC

- Java 进程 CPU 峰值最高 `7.88%`
- MySQL 进程 CPU 峰值最高 `8.06%`
- 系统 CPU 峰值最高 `42.44%`
- 系统可用内存始终大于 `2399MB`
- 三档场景 `FGC` 增量都为 `0`

结论：

- 当前不是 CPU 打满
- 也不是 Full GC 或明显内存压力导致 RT 恶化

### 6.3 数据库并发没有随线程数同比提升

- `Threads_connected` 峰值始终是 `11`
- `Threads_running` 峰值始终在 `11~12`

说明压测线程增加后，数据库实际活跃并发并没有同步上升，请求在进入数据库之前或数据库内部已经开始排队。

### 6.4 行锁等待持续存在

- `Innodb_row_lock_current_waits` 峰值达到 `8`
- `data_lock_waits` 峰值达到 `36`
- `RowLockWaits` 每轮都增加 `13000+`
- `RowLockTime` 每轮都增加 `46~47` 万毫秒

说明活跃事务中持续存在明显行锁等待。

### 6.5 热点 SQL 很明确

最重 SQL：

```text
UPDATE tb_seckill_voucher SET stock = stock - ? WHERE ( voucher_id = ? AND stock > ? )
```

同时：

- 查询秒杀券 SQL 平均约 `0.252ms`
- 查询是否已下单 SQL 平均约 `0.230ms`
- 插入订单 SQL 平均约 `0.247ms`

结论：

- 真正的热点在库存扣减这条单行 `UPDATE`
- 订单插入不是当前瓶颈

## 7. 最终结论

`mysql-only` 当前的核心瓶颈是：

- `tb_seckill_voucher` 热点库存行的 InnoDB 行锁竞争

可以基本排除：

- Java CPU 打满
- MySQL CPU 打满
- 系统内存不足
- Full GC 抖动

更准确地说，问题不是“机器跑不动”，而是“所有请求都去竞争同一条库存更新 SQL”，导致热点库存行成为主瓶颈。

## 8. 后续待补

- `mysql-only flash-sale`
- 与 `db-cache`、`db-cache-mq` 的统一高反差对照分析
