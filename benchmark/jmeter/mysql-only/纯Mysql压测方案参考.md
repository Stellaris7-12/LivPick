# 纯Mysql压测方案参考

## 1. 目标

这份文件用于固定 `MySQL-only` 分支的压测核心配置，作为后续：

- `MySQL + Redis`
- `MySQL + Redis + Kafka`

两套方案的对照基线。

后续压测时，除“秒杀链路实现方式”外，其他关键变量尽量保持不变。

## 2. 环境固定项

| 项目 | 配置 |
| --- | --- |
| 应用分支 | `MySQL-only` |
| JDK | `11` |
| 压测工具 | `JMeter 5.6.3` |
| 应用端口 | `8081` |
| 数据库 | `livpick_mysql_only` |
| 压测接口 | `POST /voucher-order/seckill/7` |
| 身份方式 | `X-Benchmark-User-Id` |
| 登录绕过开关 | `--app.benchmark.skip-login-check=true` |

## 3. 应用启动配置

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

## 4. JMeter线程组核心配置

| 项目 | 配置 |
| --- | --- |
| Loop Count | `Forever` |
| Scheduler | `true` |
| Delay | 空 |
| 停止方式 | 由 `durationSeconds` 控制总时长 |
| 并发控制 | 由 `threads` 控制 |
| 爬升控制 | 由 `rampUpSeconds` 控制 |

说明：

- 本次压测不是固定请求总数模型
- 每个线程会持续循环发请求，直到达到压测时长
- 最终样本数以实际执行结果为准

## 5. Warmup实际配置

| 项目 | 配置 |
| --- | --- |
| 场景文件 | `baseline-throughput.jmx` |
| `threads` | `10` |
| `rampUpSeconds` | `2` |
| `durationSeconds` | `10` |
| `Loop Count` | `Forever` |
| `userCsv` | `data/user_ids_unique.csv` |

## 6. 正式压测实际配置

### 6.1 吞吐基线

| 场景 | threads | rampUpSeconds | durationSeconds | Loop Count | userCsv |
| --- | ---: | ---: | ---: | --- | --- |
| `baseline-50` | `50` | `5` | `60` | `Forever` | `data/user_ids_unique.csv` |
| `baseline-100` | `100` | `5` | `60` | `Forever` | `data/user_ids_unique.csv` |
| `baseline-200` | `200` | `5` | `60` | `Forever` | `data/user_ids_unique.csv` |
| `baseline-500` | `500` | `5` | `60` | `Forever` | `data/user_ids_unique.csv` |

### 6.2 库存不超卖校验

| 场景 | threads | rampUpSeconds | durationSeconds | Loop Count | userCsv |
| --- | ---: | ---: | ---: | --- | --- |
| `oversell-100` | `100` | `5` | `60` | `Forever` | `data/user_ids_unique.csv` |

### 6.3 一人一单校验

| 场景 | threads | rampUpSeconds | durationSeconds | Loop Count | userCsv |
| --- | ---: | ---: | ---: | --- | --- |
| `one-user-one-order-100` | `100` | `5` | `60` | `Forever` | `data/user_ids_repeat.csv` |

## 7. 带监控压测实际配置

| 场景 | threads | rampUpSeconds | durationSeconds | EnableMonitoring | SamplingIntervalSeconds |
| --- | ---: | ---: | ---: | --- | ---: |
| `baseline-100` | `100` | `5` | `60` | `true` | `2` |
| `baseline-200` | `200` | `5` | `60` | `true` | `2` |
| `baseline-500` | `500` | `5` | `60` | `true` | `2` |

## 8. 用户数据固定项

| 文件 | 用途 |
| --- | --- |
| `data/user_ids_unique.csv` | 吞吐基线、超卖校验 |
| `data/user_ids_repeat.csv` | 一人一单校验 |

说明：

- `user_ids_unique.csv` 中每行是不同用户
- `user_ids_repeat.csv` 中同一用户会重复出现

## 9. 数据库重置固定项

| 场景 | SQL |
| --- | --- |
| 大库存场景 | `sql/reset_stock_large.sql` |
| 小库存场景 | `sql/reset_stock_small.sql` |
| 压测后核对 | `sql/check_results.sql` |

## 10. 后续两套方案必须保持不变的控制变量

- JDK版本
- JMeter版本
- 接口路径
- `voucherId = 7`
- `threads`
- `rampUpSeconds`
- `durationSeconds`
- Warmup配置
- 用户数据文件
- 数据库重置方式
- 成功/失败判定口径
- 统计指标口径
- 监控场景档位

## 11. 创建新分支时建议沿用的文件

### 11.1 必须沿用

| 文件 | 主要作用 |
| --- | --- |
| `baseline-throughput.jmx` | 吞吐基线压测场景，保证三套方案都用同一套线程组和请求模型。 |
| `oversell-check.jmx` | 库存不超卖校验场景，保证三套方案都用同一套正确性校验方式。 |
| `one-user-one-order.jmx` | 一人一单校验场景，保证三套方案都用同一套重复下单测试方式。 |
| `run-jmeter-benchmark.ps1` | 一键执行脚本，统一 Warmup、正式压测、结果汇总方式。后续只允许按新分支实际情况做最小必要调整。 |
| `collect-runtime-monitor.ps1` | 带监控场景的伴随采样脚本，统一 JVM、系统、MySQL 证据采集口径。 |
| `data/user_ids_unique.csv` | 吞吐基线和超卖校验使用的唯一用户集，保证不同方案用同一批压测用户。 |
| `data/user_ids_repeat.csv` | 一人一单校验使用的重复用户集，保证不同方案用同一批重复请求模式。 |
| `sql/reset_stock_large.sql` | 大库存场景的数据重置脚本，保证吞吐基线前的数据库初始状态一致。 |
| `sql/reset_stock_small.sql` | 小库存场景的数据重置脚本，保证超卖校验前的数据库初始状态一致。 |
| `sql/check_results.sql` | 压测后数据库核对脚本，保证订单数、库存核对口径一致。 |

### 11.2 建议沿用

| 文件 | 主要作用 |
| --- | --- |
| `纯Mysql压测方案参考.md` | 作为后续分支的控制变量参考表，方便逐项核对压测参数是否漂移。 |
| `report/mysql-only-benchmark-report.md` | 作为基线结果报告，后续 Redis / Kafka 方案压测完成后可以直接拿来做横向对照。 |

### 11.3 不建议直接照搬的内容

| 项目 | 原因 |
| --- | --- |
| `livpick_mysql_only` 数据库名 | 后续分支最好改成各自独立库，避免相互污染数据。 |
| 应用启动命令中的分支实现细节 | 后续分支的中间件地址、开关项可能不同，但压测口径要保持一致。 |
| 已有 `target/benchmark/jmeter/...` 结果目录 | 这些是运行产物，只保留参考，不要直接带入新分支。 |

## 12. 建议统一对比的场景

- 吞吐对比：`baseline-50`、`baseline-100`、`baseline-200`、`baseline-500`
- 正确性对比：`oversell-100`
- 一人一单对比：`one-user-one-order-100`
- 瓶颈对比：`baseline-100`、`baseline-200`、`baseline-500` + `EnableMonitoring`

## 13. 本次MySQL-only基线结论

- 吞吐平台约 `220~230 QPS`
- `100` 线程后吞吐接近封顶
- 并发继续升高时，RT显著恶化
- 主瓶颈是 `tb_seckill_voucher` 热点库存行锁竞争
