# MySQL-only 秒杀压测与瓶颈诊断报告

## 1. 结论摘要

本分支已经完成两类验证：

1. 业务正确性
   - 库存不超卖成立
   - 一人一单成立
2. 性能与瓶颈定位
   - `MySQL-only` 基线吞吐约为 `200+ QPS`
   - 并发从 `100` 继续拉高到 `200`、`500` 后，QPS 基本封顶，但 RT 明显恶化
   - 主瓶颈是 `tb_seckill_voucher` 热点库存行的 InnoDB 行锁竞争
   - 连接池排队是次级现象，不是主因
   - CPU、内存、GC 不是当前主瓶颈

## 2. 环境与口径

- 分支：`feature/seckill-db-only`
- 应用：`target/LivPick-0.0.1-SNAPSHOT.jar`
- JDK：`11`
- 数据库：`livpick_mysql_only`
- 压测工具：`JMeter 5.6.3`
- 压测接口：`POST /voucher-order/seckill/7`
- 身份注入：`X-Benchmark-User-Id`
- 正式脚本：`benchmark/jmeter/mysql-only/run-jmeter-benchmark.ps1`
- 监控脚本：`benchmark/jmeter/mysql-only/collect-runtime-monitor.ps1`

固定控制变量：

- 同一机器
- 同一 JDK
- 同一数据库
- 同一券 ID：`7`
- 同一 `rampUpSeconds=5`
- 同一 `durationSeconds=60`
- 同一用户数据文件

## 3. 正式压测结果

正式压测结果目录：

- `target/benchmark/jmeter/run-20260527-000448`

### 3.1 吞吐基线

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 最终库存 | 最终订单数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 12663 | 12663 | 0 | 211.72 | 226.50 | 281 | 316 | 590 | 0.00% | 37337 | 12663 |
| 100 | 13101 | 13101 | 0 | 219.02 | 438.59 | 546 | 648 | 840 | 0.00% | 36899 | 13101 |
| 200 | 11661 | 11661 | 0 | 195.08 | 990.25 | 1647 | 1998 | 3416 | 0.00% | 38339 | 11661 |
| 500 | 12817 | 12817 | 0 | 214.58 | 2265.56 | 3290 | 3355 | 4200 | 0.00% | 37183 | 12817 |

结论：

- 本机环境下，`100` 线程时吞吐最高，为 `219.02 QPS`
- `100 -> 200 -> 500` 后，吞吐没有继续稳定提升
- 但平均响应时间和尾延迟显著恶化

### 3.2 库存不超卖校验

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终库存 | 最终订单数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 22806 | 3000 | 19806 | 381.31 | 251.52 | 467 | 521 | 616 | 86.85% | `Out of stock=19806` | 0 | 3000 | 通过 |

数据库核对结果：

- 最终库存：`0`
- 最终订单数：`3000`
- 没有负库存

### 3.3 一人一单校验

场景数据：

- 唯一用户数：`1000`
- 每个用户重复 `5` 次
- 总请求：`5000`

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终订单数 | 重复用户数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 5000 | 1000 | 4000 | 257.55 | 341.39 | 462 | 484 | 826 | 80.00% | `Duplicate orders are not allowed=4000` | 1000 | 0 | 通过 |

数据库核对结果：

- 最终订单数：`1000`
- 重复下单用户数：`0`

## 4. 带监控压测结果

本轮瓶颈定位只跑三档基线：

- `target/benchmark/jmeter/run-20260527-113409`
- `target/benchmark/jmeter/run-20260527-114025`
- `target/benchmark/jmeter/run-20260527-114347`

| 场景 | 样本数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | Max App CPU | Max MySQL CPU | Max System CPU | Min Free Mem(MB) | Max Threads Connected | Max Threads Running | Max Row Lock Waits | Max Data Lock Waits | Max Old Gen | YGC 增量 | FGC 增量 | GC 时间增量(s) | Row Lock Waits 增量 | Row Lock Time 增量(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline-100 | 13114 | 219.29 | 438.59 | 496 | 571 | 1329 | 6.81% | 8.06% | 42.44% | 2577 | 11 | 11 | 8 | 36 | 69.67% | 28 | 0 | 0.056 | 13089 | 467018 |
| baseline-200 | 13572 | 227.14 | 850.67 | 971 | 1656 | 2685 | 7.88% | 6.00% | 22.22% | 2519 | 11 | 12 | 8 | 36 | 74.88% | 30 | 0 | 0.095 | 13525 | 469224 |
| baseline-500 | 13737 | 230.10 | 2109.61 | 2612 | 3061 | 3905 | 7.69% | 6.06% | 33.42% | 2399 | 11 | 12 | 8 | 36 | 77.72% | 32 | 0 | 0.102 | 13614 | 475740 |

## 5. 瓶颈判断

### 5.1 先看现象

从 `100 -> 200 -> 500` 线程：

- QPS 只从 `219.29` 增长到 `230.10`
- Avg 从 `438.59ms` 增长到 `2109.61ms`
- P99 从 `571ms` 增长到 `3061ms`

这说明系统在 `100` 线程附近已经接近吞吐平台期。后续增加线程主要带来排队和等待，而不是有效吞吐增长。

### 5.2 排除 CPU / 内存 / GC

应用和系统侧没有出现资源打满特征：

- Java 进程 CPU 峰值最高 `7.88%`
- MySQL 进程 CPU 峰值最高 `8.06%`
- 系统 CPU 峰值最高 `42.44%`
- 系统可用内存始终大于 `2399MB`
- 三档场景 `FGC` 增量都为 `0`
- GC 总耗时增量仅 `0.056s`、`0.095s`、`0.102s`

结论：当前不是 CPU 打满，也不是 Full GC 或明显内存压力导致的 RT 恶化。

### 5.3 数据库并发没有随线程数同步提升

- `Threads_connected` 峰值始终是 `11`
- `Threads_running` 峰值始终在 `11~12`

说明随着压测线程从 `100` 提高到 `500`，数据库实际活跃并发并没有同比增加。请求在进入数据库之前，或者在数据库内部已经开始排队。

### 5.4 行锁等待持续存在

- `Innodb_row_lock_current_waits` 峰值始终达到 `8`
- `data_lock_waits` 峰值达到 `36`
- `RowLockWaits` 每轮都增加 `13000+`
- `RowLockTime` 每轮都增加 `46~47` 万毫秒

这说明活跃事务中持续存在明显的行锁等待。

### 5.5 热点 SQL 很明确

`baseline-500` 的 `statement-digest.txt` 显示：

```text
UPDATE tb_seckill_voucher SET stock = stock - ? WHERE ( voucher_id = ? AND stock > ? )
COUNT_STAR = 159101
total_seconds = 5317.67
avg_ms = 33.423
```

同一轮里：

- 查询秒杀券 SQL 平均 `0.252ms`
- 查询是否已下单 SQL 平均 `0.230ms`
- 插入订单 SQL 平均 `0.247ms`

`lock-diagnostics.txt` 也显示：

- `tb_seckill_voucher` 是等待最重的表
- `tb_voucher_order` 次之

## 6. 最终结论

主瓶颈是：

- `tb_seckill_voucher` 热点库存行的 InnoDB 行锁竞争

次级现象是：

- 有限数据库并发能力下的连接池/请求排队

可基本排除的方向：

- Java CPU 打满
- MySQL CPU 打满
- 系统内存不足
- Full GC 抖动
- MySQL `max_connections` 被打满

更准确地说，当前 `MySQL-only` 方案的问题不是“机器跑不动”，而是“所有请求都去竞争同一条库存更新 SQL”，导致热点库存行成为主瓶颈。

## 7. 对后续优化方案的启发

这份基线报告说明了后续优化的真正目标：

1. 不要只靠增加线程数提升吞吐
2. 优先减少数据库热点库存行竞争
3. 后续 `MySQL + Redis`、`MySQL + Redis + Kafka` 方案必须沿用同一套压测口径和伴随指标

如果要做简历或答辩量化，可以直接引用：

- `MySQL-only` 基线约 `220~230 QPS`
- 并发继续提高时，吞吐基本封顶，但 `P99` 升到 `3s` 级
- 瓶颈定位为数据库热点库存行竞争，而不是 CPU / GC 资源耗尽

## 8. 如何复跑

启动应用：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

执行完整压测：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario all
```

执行带监控场景：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario baseline-500 -EnableMonitoring
```

主要产物位置：

- `target/benchmark/jmeter/run-<timestamp>/aggregate-summary.csv`
- `target/benchmark/jmeter/run-<timestamp>/<scenario>/*.jtl`
- `target/benchmark/jmeter/run-<timestamp>/<scenario>/dashboard/`
- `target/benchmark/jmeter/run-<timestamp>/<scenario>/runtime-monitor.csv`
- `target/benchmark/jmeter/run-<timestamp>/<scenario>/statement-digest.txt`
- `target/benchmark/jmeter/run-<timestamp>/<scenario>/lock-diagnostics.txt`
