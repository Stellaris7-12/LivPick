# DB-Cache 本地环境准备

本目录用于后续 `MySQL + Redis` 架构压测前的环境初始化。

## 1. Redis（Docker）

在项目根目录执行：

```powershell
$env:REDIS_PORT = "6380"
docker compose -f docker-compose.redis.yml up -d
```

如果需要密码：

```powershell
$env:REDIS_PORT = "6380"
$env:REDIS_PASSWORD = "redis1234"
docker compose -f docker-compose.redis.yml up -d
```

应用侧继续使用已有环境变量：

- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`

推荐当前 `db-cache` 环境使用：

- `REDIS_HOST=127.0.0.1`
- `REDIS_PORT=6380`

由于 `RedissonConfig` 已改为读取 `spring.redis` 配置，`StringRedisTemplate` 与 `RedissonClient` 会共用同一套 Redis 连接信息。

## 2. MySQL 压测库

推荐单独创建 `livpick_db_cache`，避免污染其它基线库。

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -p < benchmark\jmeter\db-cache\sql\create_database.sql
mysql -h 127.0.0.1 -P 3306 -u root -D livpick_db_cache < src\main\resources\db\hmdp2.sql
mysql -h 127.0.0.1 -P 3306 -u root -D livpick_db_cache < benchmark\jmeter\db-cache\sql\patch_schema.sql
```

## 3. 常用 SQL

- 大库存压测重置：`sql\reset_stock_large.sql`
- 小库存正确性压测重置：`sql\reset_stock_small.sql`
- 压测后核对：`sql\check_results.sql`

这些 SQL 与 `mysql-only` 口径对齐，便于后续横向对比。
