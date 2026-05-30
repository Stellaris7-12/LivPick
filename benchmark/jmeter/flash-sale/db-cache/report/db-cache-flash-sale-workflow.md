# DB-Cache Flash-Sale 压测流程

## 1. 目标

本流程用于执行 `MySQL + Redis` 架构下更贴近真实秒杀业务的高反差压测，并配套采集：

- JMeter 吞吐量、RT、P95、P99、错误分布
- MySQL 行锁等待、热点 SQL
- Redis CPU、内存、Ops/s、Keyspace Hits
- JVM GC 与系统 CPU / 可用内存

本流程对应的正式结果目录为：

```text
target/benchmark/jmeter/flash-sale/db-cache/run-20260530-110438
```

配套报告见：

- `benchmark/jmeter/flash-sale/db-cache/report/db-cache-flash-sale-benchmark-report.md`

原有 `db-cache` 基准压测与缓存读场景报告见：

- `benchmark/jmeter/db-cache/report/db-cache-benchmark-report.md`

## 2. 运行前准备

### 2.1 环境确认

- JDK 11
- MySQL 8，本机 `127.0.0.1:3306`
- Redis Docker 容器 `livpick-redis-db-cache`
- JMeter：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`
- 数据库：`livpick_db_cache`

### 2.2 应用启动参数

本次压测使用的应用进程启动参数为：

```text
"C:\Program Files\Java\jdk-11\bin\java.exe" -jar target\LivPick-0.0.1-SNAPSHOT.jar
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

### 2.3 运行前检查项

1. 检查 `8081` 端口是否监听。
2. 检查 Redis 容器 `livpick-redis-db-cache` 是否运行。
3. 检查 `/benchmark/metrics` 接口是否可访问。
4. 检查 `voucher_id = 7` 在 `livpick_db_cache` 中存在。
5. 检查 JMeter 路径下 `bin\jmeter.bat` 存在。

## 3. 场景矩阵

本次 flash-sale 套件共执行 4 个场景：

| 场景名 | 用户池 | 库存 | 线程数 | Ramp-Up | 持续时间 | 说明 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `flash-burst-5k-100` | 5000 | 100 | 1000 | 3s | 10s | 短时爆发，观察极低库存下的失败过滤 |
| `flash-burst-5k-500` | 5000 | 500 | 1000 | 3s | 10s | 短时爆发，观察库存略高时的单机承载极限 |
| `flash-sustain-5k-100` | 5000 | 100 | 500 | 5s | 60s | 持续压测，观察库存快速耗尽后的入口过滤 |
| `flash-sustain-5k-500` | 5000 | 500 | 500 | 5s | 60s | 持续压测，观察更多成功单对 MySQL 的压力 |

用户池复用：

```text
benchmark/jmeter/db-cache/data/user_ids_unique.csv
```

## 4. 执行命令

### 4.1 单场景验证

首次先执行单场景冒烟：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\flash-sale\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario flash-burst-5k-100 `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

### 4.2 全量执行

全套正式执行命令：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\flash-sale\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

## 5. 运行中自动处理

每个场景执行前，runner 自动完成以下操作：

1. 执行对应库存 SQL：
   - `reset_stock_flash_100.sql`
   - `reset_stock_flash_500.sql`
2. 清理 Redis：
   - `cache:shop:*`
   - `cache:voucher:list:*`
   - `seckill:stock:{voucherId}`
   - `seckill:order:{voucherId}`
3. 从 MySQL 读取当前库存，重新写回 `seckill:stock:{voucherId}`
4. 调用 `/benchmark/admin/metrics/reset`
5. 启动运行时监控脚本
6. 执行 JMeter
7. 导出 MySQL `statement-digest`、锁等待、InnoDB 状态
8. 生成场景级 JSON 汇总与总表 `aggregate-summary.csv`

## 6. 产物目录

正式结果目录：

```text
target/benchmark/jmeter/flash-sale/db-cache/run-20260530-110438
```

每个场景下的核心产物包括：

- `<scenario>.jtl`
- `<scenario>.summary.json`
- `dashboard/`
- `runtime-monitor.csv`
- `gc-monitor.csv`
- `redis-monitor.csv`
- `statement-digest.txt`
- `lock-diagnostics.txt`
- `innodb-status.txt`
- `benchmark-metrics-before.json`
- `benchmark-metrics-after.json`
- `benchmark-metrics-delta.json`

## 7. 过程问题与修正

首次执行时发现 `flash-sale` 目录比原始 `db-cache` 目录多一层，runner 仍沿用了旧的“向上三级”项目根定位，导致路径被错误解析为：

```text
benchmark\benchmark\jmeter\flash-sale\...
```

已修复为向上四级回到项目根目录，之后场景执行正常。

## 8. 解读原则

本套件重点观察的是：

- Redis 是否在库存耗尽后承担大规模失败请求过滤
- 大量无效请求是否避免继续冲击 MySQL
- 成功订单数是否与库存严格一致
- 系统失败和业务失败能否区分开

不应只看“成功下单吞吐量”，还应重点看：

- `库存不足` 返回是否足够快
- `HttpHostConnectException` 等系统失败是否出现
- `RowLockWaitsDelta` / `RowLockTimeDeltaMs` 是否明显低于纯数据库竞争型场景
- `RedisHitsDelta` 是否足够高，证明失败请求主要被 Redis 拦截
