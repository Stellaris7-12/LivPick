# DB-Cache Flash-Sale 压测说明

本目录用于补充 `MySQL + Redis` 架构下更贴近真实秒杀业务的高反差压测场景。
这组场景不替代原有 `benchmark/jmeter/db-cache/` 的基础压测，而是专门用于观察“有限库存 + 海量请求”下 Redis 在请求过滤、失败快速返回、数据库减压方面的作用。

## 1. 目录目的

- 与原有基础压测目录分离，避免混用旧场景和新场景
- 聚焦 `db-cache` 当前实现，不提前绑定 `mysql-only` 和 `db-cache-mq`
- 用更低库存、更高并发的方式放大 Redis 的并发承载价值

## 2. 场景矩阵

本套件固定使用 `5000` 用户池，对应 CSV 先复用：

```text
benchmark/jmeter/db-cache/data/user_ids_unique.csv
```

当前可执行场景：

| 场景名 | 类型 | 用户池 | 库存 | 线程数 | Ramp-Up | 持续时间 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 短时爆发 | 5000 | 100 | 1000 | 3s | 10s |
| `flash-burst-5k-500` | 短时爆发 | 5000 | 500 | 1000 | 3s | 10s |
| `flash-sustain-5k-100` | 持续压测 | 5000 | 100 | 500 | 5s | 60s |
| `flash-sustain-5k-500` | 持续压测 | 5000 | 500 | 500 | 5s | 60s |

约束说明：

- `1000` 线程仅用于短时爆发场景
- `60s` 持续压测默认限制在 `500` 线程，避免单机 JMeter、自身应用、MySQL、Redis 同机运行时互相污染结果

## 3. 前置条件

- JDK 11
- MySQL 8，本机 `127.0.0.1:3306`
- Redis Docker 容器已启动
- JMeter：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`
- 应用已按 `db-cache` 架构启动，并开启 benchmark 管理接口

推荐应用启动参数：

```text
--server.port=8081
--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_db_cache?useSSL=false&serverTimezone=UTC
--spring.redis.host=127.0.0.1
--spring.redis.port=6380
--app.benchmark.enabled=true
--app.benchmark.skip-login-check=true
--app.seckill.consumer.enabled=false
```

## 4. SQL 重置脚本

库存重置脚本位于：

- `benchmark/jmeter/flash-sale/db-cache/sql/reset_stock_flash_100.sql`
- `benchmark/jmeter/flash-sale/db-cache/sql/reset_stock_flash_500.sql`

它们会执行：

- 重置 `tb_seckill_voucher.stock`
- 确保秒杀时间窗口有效
- 清空当前券的订单数据
- 重置 `tb_voucher_order` 自增起点

## 5. 运行命令

执行全部 flash-sale 场景：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\flash-sale\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

只执行单场景：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\flash-sale\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario flash-burst-5k-100 `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

可选参数：

- `-MySqlDb livpick_db_cache`
- `-RedisContainer livpick-redis-db-cache`
- `-VoucherId 7`
- `-UserCsv benchmark/jmeter/db-cache/data/user_ids_unique.csv`

## 6. 脚本自动处理内容

每个场景执行前，runner 会自动完成：

- 重置数据库库存与订单数据
- 清理 Redis 中秒杀库存、下单集合和商户缓存相关键
- 重新写入 `seckill:stock:{voucherId}`
- 重置 benchmark 计数器

若启用 `-EnableMonitoring`，还会采集：

- `runtime-monitor.csv`
- `gc-monitor.csv`
- `redis-monitor.csv`
- `statement-digest.txt`
- `lock-diagnostics.txt`
- `innodb-status.txt`
- `benchmark-metrics-before.json`
- `benchmark-metrics-after.json`
- `benchmark-metrics-delta.json`

## 7. 结果输出

结果目录：

```text
target/benchmark/jmeter/flash-sale/db-cache/run-<timestamp>/
```

重点关注：

- `aggregate-summary.csv`
- `<scenario>/<scenario>.summary.json`
- `<scenario>/<scenario>.jtl`
- `<scenario>/dashboard/`
- `<scenario>/benchmark-metrics-delta.json`
- `<scenario>/runtime-monitor.csv`
- `<scenario>/redis-monitor.csv`
- `<scenario>/statement-digest.txt`
- `<scenario>/lock-diagnostics.txt`

## 8. 结果解读建议

这组场景不是为了证明“成功写单吞吐量会无限增长”，而是为了观察：

- 库存耗尽后失败请求是否能快速在 Redis 层被拦截
- 大量无效请求是否避免继续冲击 MySQL
- `库存不足` 和 `不能重复下单` 这类业务失败是否保持低 RT
- 数据库热点行锁等待是否明显集中在少量成功路径上

如果 `flash-burst-5k-100` 与 `flash-sustain-5k-100` 下：

- Redis `Ops/s` 上升明显
- MySQL 写锁等待仍集中在少量成功请求
- 库存耗尽后 RT 没有随并发线性恶化

那么这才更能体现 `db-cache` 架构相对纯 MySQL 的价值。
