# DB-Cache 压测说明

本目录用于 `MySQL + Redis` 秒杀架构的完整压测。

目标包括：

- 秒杀基线吞吐量、RT、P95、P99
- 超卖校验
- 一人一单校验
- 商户缓存命中、缓存穿透、热点 Key 逻辑过期
- MySQL / Redis / JVM / 系统指标采样

## 1. 前置条件

- JDK 11
- MySQL 8，本机 `127.0.0.1:3306`
- Docker 已启动
- Redis 容器：`livpick-redis-db-cache`
- JMeter：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`

## 2. 初始化数据库

推荐使用独立库 `livpick_db_cache`，不要复用 `hmdp` 或 `livpick_mysql_only`。

建库：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 < benchmark\jmeter\db-cache\sql\create_database.sql
```

导入主 schema：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 livpick_db_cache < src\main\resources\db\hmdp2.sql
```

打补丁：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 livpick_db_cache < benchmark\jmeter\db-cache\sql\patch_schema.sql
```

## 3. 启动 Redis

本分支使用独立 Redis 容器，不复用其他分支正在运行的 Redis。

```powershell
docker compose -f docker-compose.redis.yml up -d
```

确认：

```powershell
docker ps --filter "name=livpick-redis-db-cache"
docker exec livpick-redis-db-cache redis-cli PING
```

## 4. 启动应用

压测前先停掉占用 `8081` 的旧实例，尤其是 `livpick_mysql_only` 那份应用，否则会污染结果。

启动命令：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --server.port=8081 `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_db_cache?useSSL=false&serverTimezone=UTC `
  --spring.datasource.username=root `
  --spring.datasource.password=heyunhui2856 `
  --spring.redis.host=127.0.0.1 `
  --spring.redis.port=6380 `
  --app.benchmark.enabled=true `
  --app.benchmark.skip-login-check=true `
  --app.seckill.consumer.enabled=false
```

说明：

- `app.benchmark.enabled=true`：开启 `/benchmark/**` 管理接口
- `app.benchmark.skip-login-check=true`：允许 JMeter 通过 `X-Benchmark-User-Id` 注入身份
- `app.seckill.consumer.enabled=false`：禁用旧 Stream 消费线程

## 5. Benchmark 管理接口

- `POST /benchmark/admin/metrics/reset`
- `GET /benchmark/metrics`
- `POST /benchmark/admin/cache/shop/{id}/warm`
- `POST /benchmark/admin/cache/shop/{id}/expire`

当前用于缓存观测的计数器：

- `totalRequests`
- `cacheHit`
- `nullHit`
- `cacheMiss`
- `dbFallback`
- `staleHit`
- `rebuildScheduled`
- `rebuildSuccess`
- `rebuildFailure`
- `rebuildLockHit`
- `rebuildLockMiss`

## 6. 可执行场景

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

## 7. 运行命令

完整压测：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario all `
  -EnableMonitoring `
  -AppBaseUrl http://127.0.0.1:8081
```

单场景验证：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\db-cache\run-jmeter-benchmark.ps1 `
  -Scenario baseline-50 `
  -AppBaseUrl http://127.0.0.1:8081
```

脚本默认 JMeter 路径已经指向：

```text
C:\Users\heyunhui\Documents\apache-jmeter-5.6.3
```

## 8. 运行时自动处理

脚本会自动做这些事情：

- 重置 `tb_seckill_voucher` 与 `tb_voucher_order`
- 重置 Redis 秒杀库存键和用户下单集合
- 预热 / 强制过期商户缓存
- 重置 benchmark 计数器
- 采集 `runtime-monitor.csv`
- 采集 `gc-monitor.csv`
- 采集 `redis-monitor.csv`
- 导出 `statement-digest.txt`
- 导出 `lock-diagnostics.txt`
- 导出 `innodb-status.txt`
- 导出 `benchmark-metrics-before/after/delta.json`

## 9. 结果目录

每轮压测输出到：

```text
target/benchmark/jmeter/db-cache/run-<timestamp>/
```

本次有效结果目录：

```text
target/benchmark/jmeter/db-cache/run-20260529-114434/
```

重点文件：

- `aggregate-summary.csv`
- `<scenario>/<scenario>.summary.json`
- `<scenario>/<scenario>.jtl`
- `<scenario>/dashboard/`
- `<scenario>/runtime-monitor.csv`
- `<scenario>/redis-monitor.csv`
- `<scenario>/statement-digest.txt`
- `<scenario>/lock-diagnostics.txt`
- `<scenario>/benchmark-metrics-delta.json`

## 10. 报告

正式报告见：

- `benchmark/jmeter/db-cache/report/db-cache-benchmark-report.md`
