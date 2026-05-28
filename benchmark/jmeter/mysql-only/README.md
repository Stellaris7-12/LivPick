# MySQL-only JMeter 压测说明

## 1. 看这两个文件

- 操作说明：当前文件
- 最终结果与瓶颈结论：[report/mysql-only-benchmark-report.md](C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/mysql-only/report/mysql-only-benchmark-report.md:1)

## 2. 本次实际参数配置

这次压测模型不是“固定请求数”，而是：

- `Loop Count = Forever`
- `Scheduler = true`
- 到 `durationSeconds` 后统一停止

也就是说，线程会持续循环发请求，直到压测时长结束，最终样本数是实际跑出来的结果，不是预先写死的。

### Warmup

- 使用文件：`baseline-throughput.jmx`
- `threads = 10`
- `rampUpSeconds = 2`
- `durationSeconds = 10`
- `Loop Count = Forever`
- `userCsv = data/user_ids_unique.csv`

### 正式压测：吞吐基线

- `baseline-50`
  - `threads = 50`
  - `rampUpSeconds = 5`
  - `durationSeconds = 60`
  - `Loop Count = Forever`
  - `userCsv = data/user_ids_unique.csv`
- `baseline-100`
  - `threads = 100`
  - `rampUpSeconds = 5`
  - `durationSeconds = 60`
  - `Loop Count = Forever`
  - `userCsv = data/user_ids_unique.csv`
- `baseline-200`
  - `threads = 200`
  - `rampUpSeconds = 5`
  - `durationSeconds = 60`
  - `Loop Count = Forever`
  - `userCsv = data/user_ids_unique.csv`
- `baseline-500`
  - `threads = 500`
  - `rampUpSeconds = 5`
  - `durationSeconds = 60`
  - `Loop Count = Forever`
  - `userCsv = data/user_ids_unique.csv`

### 正式压测：库存不超卖

- `oversell-100`
  - `threads = 100`
  - `rampUpSeconds = 5`
  - `durationSeconds = 60`
  - `Loop Count = Forever`
  - `userCsv = data/user_ids_unique.csv`

### 正式压测：一人一单

- `one-user-one-order-100`
  - `threads = 100`
  - `rampUpSeconds = 5`
  - `durationSeconds = 60`
  - `Loop Count = Forever`
  - `userCsv = data/user_ids_repeat.csv`

### 带监控压测

这次用于瓶颈定位的实际场景：

- `baseline-100 -EnableMonitoring`
- `baseline-200 -EnableMonitoring`
- `baseline-500 -EnableMonitoring`

附加参数：

- `SamplingIntervalSeconds = 2`

## 3. 目录作用

- `baseline-throughput.jmx`
  - 吞吐基线
- `oversell-check.jmx`
  - 库存不超卖
- `one-user-one-order.jmx`
  - 一人一单
- `run-jmeter-benchmark.ps1`
  - 一键回放脚本
- `collect-runtime-monitor.ps1`
  - 带监控时的伴随采样脚本
- `data/`
  - 压测用户数据
- `sql/`
  - 数据重置与核对 SQL
- `report/mysql-only-benchmark-report.md`
  - 合并后的正式报告

## 4. 启动应用

```powershell
& 'C:\Program Files\Java\jdk-11\bin\java.exe' `
  -jar target\LivPick-0.0.1-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_mysql_only?useSSL=false&serverTimezone=UTC `
  --app.benchmark.skip-login-check=true
```

默认 JMeter 路径：

- `C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`

## 5. 常用命令

完整压测：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario all
```

单独跑一档：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario baseline-100
```

带监控跑一档：

```powershell
powershell -ExecutionPolicy Bypass -File benchmark\jmeter\mysql-only\run-jmeter-benchmark.ps1 -Scenario baseline-500 -EnableMonitoring
```

## 6. 数据重置

大库存场景：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -D livpick_mysql_only < benchmark\jmeter\mysql-only\sql\reset_stock_large.sql
```

小库存场景：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -D livpick_mysql_only < benchmark\jmeter\mysql-only\sql\reset_stock_small.sql
```

压测后核对：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -D livpick_mysql_only < benchmark\jmeter\mysql-only\sql\check_results.sql
```

## 7. 产物位置

每轮压测都会写到：

- `target/benchmark/jmeter/run-<timestamp>/`

常看文件：

- `aggregate-summary.csv`
- `<scenario>/*.jtl`
- `<scenario>/dashboard/`
- `<scenario>/*.db-check.txt`

带监控时还会多出：

- `runtime-monitor.csv`
- `gc-monitor.csv`
- `statement-digest.txt`
- `lock-diagnostics.txt`
- `innodb-status.txt`

## 8. 推荐阅读顺序

1. 先跑脚本
2. 再看 [report/mysql-only-benchmark-report.md](C:/Users/heyunhui/IdeaProjects/LivPick/benchmark/jmeter/mysql-only/report/mysql-only-benchmark-report.md:1)
3. 需要追证据时，再回看 `target/benchmark/jmeter/run-<timestamp>/`
