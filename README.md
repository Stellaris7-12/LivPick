# LivPick MySQL-only 秒杀分支说明

## 1. 分支目标

当前分支聚焦于“只使用 MySQL 实现秒杀下单链路”。

目标有两个：

1. 提供一个不依赖 Redis 秒杀库存、Lua、消息队列的基线实现
2. 作为后续 `MySQL + Redis`、`MySQL + Redis + Kafka` 方案的压测对照组

本分支没有尝试移除项目中所有 Redis 用途，而是只剥离了“秒杀下单主链路”对 Redis 的依赖。为了让压测不再受登录态 Redis 校验影响，额外提供了压测专用请求头方案。

## 2. 秒杀方案概览

秒杀入口保持不变：

- `POST /voucher-order/seckill/{voucherId}`

当前实现位于：

- [VoucherOrderServiceImpl](src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java)

核心流程如下：

1. 校验秒杀券是否存在，以及开始/结束时间是否合法
2. 查询当前用户是否已经下过该券
3. 执行条件扣减库存
4. 创建订单
5. 若订单插入失败，则回滚整个事务

## 3. 正确性保证

### 3.1 一人一单

“一人一单”由两层保证：

1. 业务层预检查  
   在下单前查询 `tb_voucher_order`，判断当前用户是否已经下过该券。

2. 数据库唯一约束兜底  
   `tb_voucher_order` 上存在 `(voucher_id, user_id)` 唯一索引，最终以数据库约束作为并发场景下的硬保证。

这意味着即使两个相同用户请求同时通过了前置查询，最终也只会有一个插入成功，另一个会在插入订单时失败。

### 3.2 库存不超卖

库存扣减使用单条条件更新语句：

```sql
update tb_seckill_voucher
set stock = stock - 1
where voucher_id = ?
  and stock > 0;
```

只要更新成功，就说明本次请求实际拿到了库存；如果更新影响行数为 `0`，则说明库存已经耗尽。

这条语句本身是原子的，底层依赖 InnoDB 对 `tb_seckill_voucher` 热点行的行级锁控制，不会把库存扣成负数。

### 3.3 事务一致性

下单逻辑放在同一个数据库事务中：

- 已下单检查
- 条件扣减库存
- 插入订单

如果插入订单时因为唯一索引冲突失败，则事务回滚，之前的库存扣减也会一起回滚，因此不会出现“库存扣掉了，但订单没生成”的脏结果。

## 4. 压测专用登录绕过

为了避免压测阶段仍然依赖 Redis token 校验，当前分支增加了压测专用身份注入：

- 请求头：`X-Benchmark-User-Id`
- 开关：`--app.benchmark.skip-login-check=true`

相关实现位于：

- [BenchmarkUserInterceptor](src/main/java/com/livepick/utils/BenchmarkUserInterceptor.java)
- [MvcConfig](src/main/java/com/livepick/config/MvcConfig.java)

压测时直接向秒杀接口发送不同的 `X-Benchmark-User-Id` 即可，不需要提前准备 Redis 登录态。

## 5. 数据库变更

当前分支相对原始实现的关键数据库调整：

1. `tb_voucher_order.id` 改为 MySQL 自增主键
2. `tb_voucher_order` 增加 `(voucher_id, user_id)` 唯一索引

迁移脚本位于：

- [hmdp2_mysql_only_seckill.sql](src/main/resources/db/hmdp2_mysql_only_seckill.sql)

建议使用独立数据库进行压测，例如：

- `livpick_mysql_only`

## 6. JMeter 压测资产

当前分支的正式压测方案统一放在：

- [benchmark/jmeter/mysql-only](benchmark/jmeter/mysql-only)

其中包括：

- `baseline-throughput.jmx`  
  吞吐基线场景
- `oversell-check.jmx`  
  库存不超卖校验场景
- `one-user-one-order.jmx`  
  一人一单校验场景
- `run-jmeter-benchmark.ps1`  
  一键回放脚本
- `data/`  
  压测用户数据
- `sql/`  
  数据重置与结果核对脚本
- `report/mysql-only-benchmark-report.md`  
  中文正式压测报告

说明文档见：

- [benchmark/jmeter/mysql-only/README.md](benchmark/jmeter/mysql-only/README.md)

## 7. 启动与复现

### 7.1 启动应用

推荐使用 JDK 11，并连接独立压测库：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

### 7.2 执行完整压测

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario all
```

### 7.3 单独执行某个场景

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario baseline-100
```

可选场景：

- `baseline-50`
- `baseline-100`
- `baseline-200`
- `baseline-500`
- `oversell-100`
- `one-user-one-order-100`
- `all`

## 8. 当前压测结果

正式压测结果目录：

- `target/benchmark/jmeter/run-20260527-000448`

### 8.1 吞吐基线

| 并发线程数 | 样本数 | 成功数 | 失败数 | QPS | Avg(ms) | P95(ms) | P99(ms) | Max(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 12663 | 12663 | 0 | 211.72 | 226.50 | 281 | 316 | 590 |
| 100 | 13101 | 13101 | 0 | 219.02 | 438.59 | 546 | 648 | 840 |
| 200 | 11661 | 11661 | 0 | 195.08 | 990.25 | 1647 | 1998 | 3416 |
| 500 | 12817 | 12817 | 0 | 214.58 | 2265.56 | 3290 | 3355 | 4200 |

结论：

- 当前机器上，`100` 线程时吞吐达到本轮峰值
- 并发从 `100` 提高到 `200`、`500` 后，QPS 没有继续明显增长
- 但平均响应时间和尾延迟显著恶化，说明系统已经进入明显竞争区间

### 8.2 库存不超卖校验

场景结果：

- 样本数：`22806`
- 成功数：`3000`
- 失败数：`19806`
- 失败原因：全部为 `Out of stock`
- 最终库存：`0`
- 最终订单数：`3000`

结论：库存没有超卖。

### 8.3 一人一单校验

场景结果：

- 样本数：`5000`
- 成功数：`1000`
- 失败数：`4000`
- 失败原因：全部为 `Duplicate orders are not allowed`
- 最终订单数：`1000`
- 重复下单用户数：`0`

结论：一人一单成立。

## 9. 如何看待当前性能上限

当前这套 `MySQL-only` 方案的性能特征比较清晰：

1. 正确性已经成立  
   库存不超卖、一人一单、事务回滚一致性都已经通过压测校验。

2. 热点竞争明显  
   所有请求都会竞争同一张秒杀库存表中的同一行数据，这会导致高并发下响应时间迅速升高。

3. 适合作为基线方案  
   后续无论是引入 Redis 预扣库存，还是引入 Kafka 异步削峰，都可以直接和当前方案做同口径对比。

## 10. 后续对比时建议保持不变的变量

为了让后续 `MySQL + Redis`、`MySQL + Redis + Kafka` 方案的压测结果可比，建议继续保持以下变量不变：

- 同一台压测机器
- 同一 JDK 版本
- 同一秒杀券 ID
- 同一压测时长
- 同一并发档位
- 同一批压测用户数据
- 同一数据库重置方式
- 同一报表口径

建议只改变“秒杀链路实现方式”，不要同时更换压测工具、登录方案或数据规模，否则结论会变得不干净。

## 11. 如何定位性能瓶颈

仅凭 JMeter 的 `QPS / Avg / P95 / P99`，只能看到“系统变慢了”，还不能直接证明瓶颈到底在应用线程池、数据库连接池、CPU、GC 还是 MySQL 锁竞争。

更稳妥的判断方式是同时采集三类信息：

### 11.1 压测结果现象

先看外部表现：

- 并发提升后，QPS 是否继续增长
- 平均响应时间、P95、P99 是否明显恶化
- 是否出现请求失败、超时或连接异常

当前分支的现象是：

- `100` 线程附近吞吐接近峰值
- 提高到 `200`、`500` 线程后，QPS 没有同步提升
- 但响应时间显著上升

这说明系统已经进入竞争区间，但还不能仅靠这一步断言根因。

### 11.2 应用侧指标

压测时建议同时观察：

- Java 进程 CPU
- 堆内存占用
- Full GC / Young GC 次数与耗时
- Tomcat 活跃线程数、等待线程数
- 数据库连接池活跃连接数、等待连接数

如果出现以下特征，通常说明问题更偏应用层：

- CPU 长时间接近 `100%`
- GC 明显频繁，且 STW 时间上升
- Tomcat 工作线程被占满
- Hikari 连接池活跃连接打满，等待队列增长

### 11.3 MySQL 侧指标

对于当前 `MySQL-only` 秒杀方案，MySQL 指标尤其关键：

- MySQL CPU
- 活跃连接数
- 慢查询数量
- InnoDB 行锁等待时间
- 行锁等待次数
- 死锁次数
- `SHOW ENGINE INNODB STATUS`

如果出现以下特征，通常说明问题更偏数据库竞争：

- `tb_seckill_voucher` 热点行的锁等待明显增加
- 数据库 CPU 并不一定打满，但事务等待时间上升
- QPS 上不去，RT 却持续增大

### 11.4 对当前方案的合理推断

结合当前实现和已有压测结果，可以做一个“高概率推断”：

- 主要瓶颈大概率在数据库侧的热点行竞争
- 竞争点是 `tb_seckill_voucher` 中同一张秒杀券对应的那一行
- 原因是所有请求都会执行库存扣减更新，并且还会伴随订单查询与订单插入

但这仍然是推断，不是最终证据。要把结论写得更硬，需要你在下一轮压测时把应用侧和 MySQL 侧指标一起采出来。

### 11.5 推荐的排查顺序

建议按下面顺序排查：

1. 先看 JMeter 报告，确定是“QPS 上不去”还是“错误率上升”
2. 再看 Java 进程 CPU、内存、GC，判断是否是应用本身先打满
3. 再看连接池是否耗尽
4. 最后重点看 MySQL 锁等待、事务等待和慢查询

对于当前这条 `MySQL-only` 链路，如果你看到：

- 错误率很低
- QPS 基本封顶
- RT 随并发升高显著上升
- MySQL 行锁等待同步增加

那就可以比较有把握地说，瓶颈主要在数据库库存热点行竞争，而不是登录拦截、网络或 JMeter 本身。
