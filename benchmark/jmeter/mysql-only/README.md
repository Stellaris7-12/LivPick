# MySQL-only 秒杀模块 JMeter 压测说明

## 1. 说明

该目录用于存放当前 `MySQL-only` 秒杀实现的 JMeter 压测文件。

本套方案只关注以下两个核心业务目标：

- 库存不超卖
- 一人一单

同时采集以下核心性能指标：

- QPS / 吞吐量
- 样本总数
- 成功数 / 失败数
- 平均响应时间
- P95 / P99 / 最大响应时间
- 错误率
- 失败原因分桶
- 压测后数据库最终库存和订单数

本目录中的 JMeter 方案仅使用原生组件，不依赖任何 JMeter 插件。

## 2. 前置条件

执行压测前，需要满足以下条件：

1. 使用 JDK 11 启动当前项目
2. 应用连接到独立压测数据库 `livpick_mysql_only`
3. 启用秒杀接口的压测登录绕过
4. 本机可用 JMeter 5.6.3

建议启动命令：

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

当前默认的 JMeter 路径：

- `C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`

## 3. 目录结构

- `baseline-throughput.jmx`
  - 吞吐基线压测
- `oversell-check.jmx`
  - 库存不超卖校验
- `one-user-one-order.jmx`
  - 一人一单校验
- `run-jmeter-benchmark.ps1`
  - JMeter 一键回放脚本
- `data/user_ids_unique.csv`
  - 唯一用户数据，用于吞吐基线与超卖校验
- `data/user_ids_repeat.csv`
  - 重复用户数据，用于一人一单校验
- `sql/reset_stock_large.sql`
  - 基线压测前的数据重置 SQL
- `sql/reset_stock_small.sql`
  - 超卖校验前的数据重置 SQL
- `sql/check_results.sql`
  - 压测后数据库核对 SQL
- `report/mysql-only-benchmark-report.md`
  - 中文正式压测报告

## 4. 公共参数

三份 `.jmx` 都支持相同的运行参数：

- `host`
  - 默认 `127.0.0.1`
- `port`
  - 默认 `8081`
- `protocol`
  - 默认 `http`
- `voucherId`
  - 默认 `7`
- `threads`
  - 并发线程数
- `rampUpSeconds`
  - 线程爬升时间
- `durationSeconds`
  - 正式压测时长
- `userCsv`
  - 用户数据文件路径

## 5. 数据文件说明

### `user_ids_unique.csv`

- 每行一个唯一用户 ID
- 用于 `baseline-throughput.jmx`
- 用于 `oversell-check.jmx`
- 当前已准备 `50000` 个唯一用户

### `user_ids_repeat.csv`

- 同一用户会重复出现
- 用于 `one-user-one-order.jmx`
- 当前已准备 `1000` 个唯一用户
- 每个用户重复 `5` 次
- 总计 `5000` 行

## 6. 压测前数据重置

### 吞吐基线 / 一人一单

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only < benchmark\jmeter\mysql-only\sql\reset_stock_large.sql
```

### 超卖校验

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only < benchmark\jmeter\mysql-only\sql\reset_stock_small.sql
```

## 7. 一键回放

执行完整 JMeter 套件：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario all
```

单独执行某个场景：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario baseline-100
```

可选场景值：

- `baseline-50`
- `baseline-100`
- `baseline-200`
- `baseline-500`
- `oversell-100`
- `one-user-one-order-100`
- `all`

## 8. 手动执行示例

### 吞吐基线示例

```powershell
& 'C:\Users\heyunhui\Documents\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -n `
  -t benchmark\jmeter\mysql-only\baseline-throughput.jmx `
  -Jthreads=100 `
  -JrampUpSeconds=5 `
  -JdurationSeconds=60 `
  -JuserCsv=benchmark/jmeter/mysql-only/data/user_ids_unique.csv `
  -l target\benchmark\jmeter\baseline-100.jtl `
  -e -o target\benchmark\jmeter\baseline-100-dashboard
```

### 超卖校验示例

```powershell
& 'C:\Users\heyunhui\Documents\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -n `
  -t benchmark\jmeter\mysql-only\oversell-check.jmx `
  -Jthreads=100 `
  -JrampUpSeconds=5 `
  -JdurationSeconds=60 `
  -JuserCsv=benchmark/jmeter/mysql-only/data/user_ids_unique.csv `
  -l target\benchmark\jmeter\oversell-100.jtl `
  -e -o target\benchmark\jmeter\oversell-100-dashboard
```

### 一人一单校验示例

```powershell
& 'C:\Users\heyunhui\Documents\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -n `
  -t benchmark\jmeter\mysql-only\one-user-one-order.jmx `
  -Jthreads=100 `
  -JrampUpSeconds=5 `
  -JdurationSeconds=60 `
  -JuserCsv=benchmark/jmeter/mysql-only/data/user_ids_repeat.csv `
  -l target\benchmark\jmeter\one-user-one-order-100.jtl `
  -e -o target\benchmark\jmeter\one-user-one-order-100-dashboard
```

## 9. 推荐压测矩阵

### 吞吐基线

- `50` 线程
- `100` 线程
- `200` 线程
- `500` 线程

### 超卖校验

- `100` 线程

### 一人一单校验

- `100` 线程

建议统一使用：

- `rampUpSeconds=5`
- `durationSeconds=60`

## 10. 压测后核对

每轮压测结束后，建议执行：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only < benchmark\jmeter\mysql-only\sql\check_results.sql
```

对于一人一单校验，额外建议执行：

```sql
SELECT user_id, COUNT(*) AS order_count
FROM tb_voucher_order
WHERE voucher_id = 7
GROUP BY user_id
HAVING COUNT(*) > 1;
```

## 11. 正式报告

中文正式压测报告位于：

- `benchmark/jmeter/mysql-only/report/mysql-only-benchmark-report.md`

其中已经包含：

- 环境信息
- 吞吐基线结果
- 库存不超卖校验结果
- 一人一单校验结果
- 复现步骤
- JMeter 产物路径

## 12. 当前状态

当前仓库已经具备完整的 MySQL-only JMeter 压测资产：

- JMeter 测试计划
- 回放脚本
- 用户数据文件
- SQL 重置与校验脚本
- 中文正式报告

因此后续可以直接基于当前目录继续：

- 重复执行 MySQL-only 压测
- 调整线程数重新对比
- 扩展到 Redis / Kafka 分支并保持压测口径一致
