# MySQL-only 秒杀压测说明

## 1. 目标

本文档只关注当前分支的纯 MySQL 秒杀实现，不比较 Redis 或 Kafka 方案。

需要验证的内容有两类：

- 业务正确性：库存不超卖、一人一单成立
- 性能表现：QPS、平均响应时间、P95、P99、最大响应时间、成功率

## 2. 当前实现范围

纯 MySQL 秒杀链路位于 [VoucherOrderServiceImpl](../src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java)。

核心流程如下：

1. 查询秒杀券，校验活动是否存在、是否开始、是否结束
2. 按 `user_id + voucher_id` 查询是否已下单
3. 使用条件更新语句扣减库存：`stock = stock - 1 and stock > 0`
4. 插入订单
5. 依赖数据库唯一索引兜底一人一单

当前链路不再依赖以下组件：

- `seckill.lua`
- Redis Stream
- Redisson 分布式锁
- `RedisIdWorker`

订单主键改为 MySQL 自增，数据库迁移脚本位于：

- [hmdp2_mysql_only_seckill.sql](../src/main/resources/db/hmdp2_mysql_only_seckill.sql)

## 3. 数据库准备

建议使用独立压测库，例如 `livpick_mysql_only`。

初始化步骤：

1. 导入基础表结构：`src/main/resources/db/hmdp2.sql`
2. 执行纯 MySQL 秒杀迁移脚本：`src/main/resources/db/hmdp2_mysql_only_seckill.sql`

迁移内容：

- `tb_voucher_order.id` 改为 `AUTO_INCREMENT`
- 增加唯一索引 `uk_voucher_user(voucher_id, user_id)`

建议确认：

```sql
SHOW CREATE TABLE tb_voucher_order;
```

应至少看到：

```sql
PRIMARY KEY (`id`)
UNIQUE KEY `uk_voucher_user` (`voucher_id`,`user_id`)
```

## 4. 启动方式

当前压测路径不依赖 Redis 登录态。

### 4.1 登录校验绕过方式

压测时对秒杀接口携带请求头：

```http
X-Benchmark-User-Id: 1001
```

该请求头只对 `/voucher-order/seckill/**` 生效，对应拦截器位于：

- [BenchmarkUserInterceptor](../src/main/java/com/livepick/utils/BenchmarkUserInterceptor.java)

如果希望启动时完全跳过登录校验链，可以加：

```properties
app.benchmark.skip-login-check=true
```

相关配置位于：

- [MvcConfig](../src/main/java/com/livepick/config/MvcConfig.java)

### 4.2 Redisson 启动说明

当前纯 MySQL 压测不需要创建 `RedissonClient`。

只有显式配置下列属性时，才会创建 Redisson：

```properties
app.redisson.enabled=true
```

相关配置位于：

- [RedissonConfig](../src/main/java/com/livepick/config/RedissonConfig.java)

### 4.3 本次使用的启动命令

本次压测使用 JDK 11 启动打包后的 jar，并显式指定压测数据库：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

说明：

- 当前项目在 JDK 21 下会遇到 Lombok 和 `javac` 兼容问题
- 本地编译、测试、压测统一使用 JDK 11

## 5. 测试与验证

### 5.1 业务单测

纯 MySQL 秒杀核心单测：

- [VoucherOrderServiceImplTest](../src/test/java/com/livepick/VoucherOrderServiceImplTest.java)

覆盖场景：

- 秒杀券不存在
- 秒杀未开始
- 用户重复下单
- 库存不足
- 正常下单成功

执行命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn "-Dmaven.repo.local=C:\Users\heyunhui\.m2\repository" -Dtest=VoucherOrderServiceImplTest test
```

本次结果：

- `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

### 5.2 Redis 依赖测试处理

以下测试依赖 Redis 或 Redisson，当前已禁用，不纳入纯 MySQL 验证口径：

- `RedissonTest`
- `LivPickApplicationTests`
- `SeckillBenchmarkPreparationTest`

## 6. 压测工具

压测程序位于：

- [SeckillHttpBenchmarkRunner](../src/test/java/com/livepick/SeckillHttpBenchmarkRunner.java)

作用：

- 并发发送 `POST /voucher-order/seckill/{voucherId}`
- 每个请求自动携带不同的 `X-Benchmark-User-Id`
- 输出吞吐、响应时间和失败原因分桶

### 6.1 运行命令模板

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$cp=(Get-Content 'target\test.classpath' -Raw).Trim()

java -cp "target\test-classes;target\classes;$cp" `
  "-Dbenchmark.baseUrl=http://127.0.0.1:8081" `
  "-Dbenchmark.voucherId=7" `
  "-Dbenchmark.totalRequests=1000" `
  "-Dbenchmark.concurrency=100" `
  "-Dbenchmark.warmupRequests=1" `
  "-Dbenchmark.userIdStart=200000" `
  com.livepick.SeckillHttpBenchmarkRunner
```

### 6.2 压测前重置 SQL

以下 SQL 用于重置券库存和订单数据：

```sql
UPDATE tb_seckill_voucher
SET stock = 1001,
    begin_time = NOW() - INTERVAL 1 HOUR,
    end_time = NOW() + INTERVAL 2 HOUR
WHERE voucher_id = 7;

DELETE FROM tb_voucher_order WHERE voucher_id = 7;
ALTER TABLE tb_voucher_order AUTO_INCREMENT = 1011;
```

超卖校验场景使用：

```sql
UPDATE tb_seckill_voucher
SET stock = 300,
    begin_time = NOW() - INTERVAL 1 HOUR,
    end_time = NOW() + INTERVAL 2 HOUR
WHERE voucher_id = 7;

DELETE FROM tb_voucher_order WHERE voucher_id = 7;
ALTER TABLE tb_voucher_order AUTO_INCREMENT = 1011;
```

## 7. 核心压测指标

本次重点采集以下指标：

- `QPS`
- 成功下单数
- 成功率 / 失败率
- 平均响应时间
- `P50 / P95 / P99 / Max`
- 失败原因分桶
- 最终库存
- 最终订单数
- 是否超卖
- 是否出现重复下单

## 8. 压测口径

为了保证同一分支内不同并发档位可比较，本次固定如下口径：

- 同一台机器
- 同一 JVM 版本：JDK 11
- 同一 MySQL 实例
- 同一接口：`POST /voucher-order/seckill/7`
- 同一压测工具：`SeckillHttpBenchmarkRunner`
- 每次正式压测前先做 1 次 warmup
- 每个正式请求使用不同的 `X-Benchmark-User-Id`

## 9. 本次实际压测结果

### 9.1 吞吐与响应时间

| 并发数 | 正式请求数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 1000 | 1000 | 0 | 160.33 | 304.54 | 383 | 433 | 648 |
| 100 | 1000 | 1000 | 0 | 186.15 | 510.74 | 637 | 700 | 774 |
| 200 | 1000 | 1000 | 0 | 128.94 | 1398.12 | 1638 | 1670 | 1754 |

观察：

- `100` 并发时吞吐最高
- `200` 并发后，平均时延和尾时延显著上升，吞吐反而下降
- 这符合纯 MySQL 方案在库存行更新和唯一索引竞争下的锁争用特征

### 9.2 超卖校验

压测条件：

- 库存：`300`
- 正式请求：`500`
- 并发：`100`

结果：

| 指标 | 数值 |
| --- | ---: |
| 成功数 | 299 |
| 失败数 | 201 |
| 失败原因 | `Out of stock = 201` |
| 最终库存 | 0 |
| 最终订单数 | 300 |

说明：

- 正式阶段成功 `299`，再加上 `1` 次 warmup，共生成 `300` 笔订单
- 数据库最终库存为 `0`
- 没有超卖

## 10. 压测后数据库核对

每轮压测结束后建议执行：

```sql
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7;
SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;
```

若要检查某个压测用户是否重复下单，可执行：

```sql
SELECT COUNT(*) AS user_order_count
FROM tb_voucher_order
WHERE voucher_id = 7
  AND user_id = 910002;
```

预期：

- 同一用户最多 1 单
- 最终订单数不大于初始库存加 warmup 成功数

## 11. 结论

当前分支的纯 MySQL 秒杀方案已经完成以下目标：

- 使用 MySQL 条件扣减库存保证不超卖
- 使用数据库唯一索引保证一人一单
- 纯 MySQL 业务单测可在 JDK 11 下正常通过
- 可在不依赖 Redis 登录态的情况下执行秒杀压测
- 已获得当前机器上的基线吞吐和时延数据

如果后续要和 `MySQL + Redis`、`MySQL + Redis + Kafka` 做横向对比，建议沿用本文档中的同一券、同一库存、同一并发档位和同一统计指标。
