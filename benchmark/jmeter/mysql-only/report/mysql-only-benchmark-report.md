# MySQL-only 秒杀模块 JMeter 压测报告

## 1. 报告概览

本报告针对当前 `MySQL-only` 方案下的秒杀模块进行 JMeter 压测，重点验证以下两类目标：

- 库存不超卖
- 一人一单

同时采集并分析以下核心性能指标：

- 吞吐量 / QPS
- 样本总数
- 成功数 / 失败数
- 平均响应时间
- P95 / P99 / 最大响应时间
- 错误率
- 失败原因分桶
- 压测后数据库最终库存与订单数

本次正式压测基于仓库内提交的 JMeter 文件执行，回放脚本如下：

- `benchmark/jmeter/mysql-only/run-jmeter-benchmark.ps1`

## 2. 压测环境

- 分支：`feature/seckill-db-only`
- 应用启动命令：

```powershell
java -jar target\LivPick-0.0.1-SNAPSHOT.jar --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC --app.benchmark.skip-login-check=true
```

- JDK：`11`
- 数据库：`livpick_mysql_only`
- JMeter 路径：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`
- 压测接口：`POST /voucher-order/seckill/7`
- 鉴权方式：请求头 `X-Benchmark-User-Id`

## 3. 控制变量

为保证本次压测结果可复现，且后续便于与 Redis / Kafka 优化方案对比，本次固定以下变量不变：

- 同一台机器
- 同一 JDK 版本
- 同一应用实例
- 同一数据库 schema
- 同一接口路径
- 同一券 ID：`7`
- 同一请求头注入方式
- 同一 JMeter 版本
- 同一 `rampUpSeconds=5`
- 同一 `durationSeconds=60`

其中：

- 吞吐基线与超卖校验使用：`data/user_ids_unique.csv`
- 一人一单校验使用：`data/user_ids_repeat.csv`

## 4. 压测产物

本次正式压测生成目录：

- `target/benchmark/jmeter/run-20260527-000448`

其中包含：

- 每个场景的 `.jtl`
- 每个场景的 HTML dashboard
- 每个场景的数据库核对结果
- 聚合汇总文件 `aggregate-summary.csv`

本次生成的 dashboard 目录如下：

- `target/benchmark/jmeter/run-20260527-000448/baseline-50/dashboard`
- `target/benchmark/jmeter/run-20260527-000448/baseline-100/dashboard`
- `target/benchmark/jmeter/run-20260527-000448/baseline-200/dashboard`
- `target/benchmark/jmeter/run-20260527-000448/baseline-500/dashboard`
- `target/benchmark/jmeter/run-20260527-000448/oversell-100/dashboard`
- `target/benchmark/jmeter/run-20260527-000448/one-user-one-order-100/dashboard`

## 5. 吞吐基线压测

基线场景压测前统一将秒杀券库存重置为 `50000`。

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 最终库存 | 最终订单数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 12663 | 12663 | 0 | 211.72 | 226.50 | 281 | 316 | 590 | 0.00% | 37337 | 12663 |
| 100 | 13101 | 13101 | 0 | 219.02 | 438.59 | 546 | 648 | 840 | 0.00% | 36899 | 13101 |
| 200 | 11661 | 11661 | 0 | 195.08 | 990.25 | 1647 | 1998 | 3416 | 0.00% | 38339 | 11661 |
| 500 | 12817 | 12817 | 0 | 214.58 | 2265.56 | 3290 | 3355 | 4200 | 0.00% | 37183 | 12817 |

结论：

- 本轮测试中，`100` 线程时吞吐最高，为 `219.02 QPS`
- 当并发从 `100` 提高到 `200`、`500` 后，吞吐并没有继续提升
- 但平均响应时间和尾延迟显著恶化，尤其是 `500` 线程下 `P95` 已达到 `3290ms`
- 说明纯 MySQL 方案在较高并发下受数据库库存行竞争和订单唯一约束竞争影响明显

## 6. 库存不超卖校验

超卖校验场景压测前统一将秒杀券库存重置为 `3000`。

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终库存 | 最终订单数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 22806 | 3000 | 19806 | 381.31 | 251.52 | 467 | 521 | 616 | 86.85% | `Out of stock=19806` | 0 | 3000 | 通过 |

数据库核对结果：

- 最终库存：`0`
- 最终订单数：`3000`
- 失败全部为库存耗尽导致的业务失败

结论：

- 未出现负库存
- 未出现最终订单数超过初始库存的情况
- 库存不超卖成立

对应数据库校验文件：

- `target/benchmark/jmeter/run-20260527-000448/oversell-100/oversell-100.db-check.txt`

## 7. 一人一单校验

一人一单场景使用重复用户数据：

- 唯一用户数：`1000`
- 每个用户重复 `5` 次
- 总请求行数：`5000`

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 | 失败原因 | 最终订单数 | 重复用户数 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| 100 | 5000 | 1000 | 4000 | 257.55 | 341.39 | 462 | 484 | 826 | 80.00% | `Duplicate orders are not allowed=4000` | 1000 | 0 | 通过 |

数据库核对结果：

- 最终订单数：`1000`
- 重复下单用户数：`0`

结论：

- 每个用户最终最多只有 1 笔订单
- 并发重复请求被正确拦截
- 一人一单成立

对应数据库校验文件：

- `target/benchmark/jmeter/run-20260527-000448/one-user-one-order-100/one-user-one-order-100.db-check.txt`

## 8. 如何复现

### 8.1 启动应用

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

### 8.2 执行完整 JMeter 压测

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario all
```

### 8.3 单独执行某个场景

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario baseline-100
```

### 8.4 查看压测结果

- JTL：`target/benchmark/jmeter/run-<timestamp>/<scenario>/`
- Dashboard：`target/benchmark/jmeter/run-<timestamp>/<scenario>/dashboard/`
- 数据库核对文件：`target/benchmark/jmeter/run-<timestamp>/<scenario>/`
- 聚合结果：`target/benchmark/jmeter/run-<timestamp>/aggregate-summary.csv`

## 9. 仓库内相关测试文件

本次压测相关文件已提交到以下位置：

- `benchmark/jmeter/mysql-only/baseline-throughput.jmx`
- `benchmark/jmeter/mysql-only/oversell-check.jmx`
- `benchmark/jmeter/mysql-only/one-user-one-order.jmx`
- `benchmark/jmeter/mysql-only/run-jmeter-benchmark.ps1`
- `benchmark/jmeter/mysql-only/sql/reset_stock_large.sql`
- `benchmark/jmeter/mysql-only/sql/reset_stock_small.sql`
- `benchmark/jmeter/mysql-only/sql/check_results.sql`
- `benchmark/jmeter/mysql-only/data/user_ids_unique.csv`
- `benchmark/jmeter/mysql-only/data/user_ids_repeat.csv`

## 10. 总结

本次基于 JMeter 的正式压测表明：

1. `MySQL-only` 方案在当前机器上可以稳定支撑约 `200+ QPS` 的秒杀请求
2. 在并发线程数达到 `100` 左右时吞吐达到本轮峰值
3. 提高到 `200`、`500` 线程后，吞吐没有进一步提升，但响应时间恶化明显
4. 库存不超卖校验通过
5. 一人一单校验通过

因此，当前分支已经具备：

- 可复现的 JMeter 压测方案
- 完整的压测配置与数据文件
- 正式压测结果
- 数据库正确性校验证据

这可以作为后续 `MySQL + Redis`、`MySQL + Redis + Kafka` 方案横向对比的基准版本。
