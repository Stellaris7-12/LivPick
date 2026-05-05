# JMeter 秒杀压测

这个目录提供了一个可直接导入 JMeter 的测试计划：`jmeter/seckill-200-users.jmx`。

## 场景

- `voucherId=7`
- `200` 个不同用户
- 每个用户只请求 `1` 次
- 通过 `Synchronizing Timer` 尽量同时放行

## 文件

- `seckill-200-users.jmx`
  - 默认压测 `http://127.0.0.1:8081/voucher-order/seckill/7`
  - 默认读取 `C:/Users/heyunhui/IdeaProjects/LivPick/load-test-tokens.csv`
  - 默认线程数 `200`
  - 默认启动时间 `20s`

## 运行前检查

- Redis 已创建消费组：

```text
XGROUP CREATE stream.orders g1 0 MKSTREAM
```

- 秒杀券 `7` 当前可抢
- Redis 中 `seckill:stock:7 = 100`
- `load-test-tokens.csv` 至少包含 `200` 个不同 token
- 这 200 个用户之前没有买过券 `7`

## 没有 CSV 时如何从 Redis 导出

仓库里已经补了一个导出器：[RedisTokenExporter.java](C:/Users/heyunhui/IdeaProjects/LivPick/src/test/java/com/livepick/RedisTokenExporter.java)。

如果你本机装了 `redis-cli`，更直接的是运行这个脚本：[export-tokens-from-redis.ps1](C:/Users/heyunhui/IdeaProjects/LivPick/jmeter/export-tokens-from-redis.ps1)。

导出前 `200` 个 token：

```powershell
powershell -ExecutionPolicy Bypass -File jmeter/export-tokens-from-redis.ps1 -Limit 200 -Output load-test-tokens.csv
```

如果 Redis 不在本机：

```powershell
powershell -ExecutionPolicy Bypass -File jmeter/export-tokens-from-redis.ps1 -Host 127.0.0.1 -Port 6379 -Limit 200 -Output load-test-tokens.csv
```

脚本会直接生成 JMeter 可用的 CSV，第一行是表头 `token`。

导出前 `200` 个 token：

```powershell
mvn "-Dmaven.repo.local=c:\\Users\\heyunhui\\Desktop\\求职\\面试准备\\AI\\.m2\\repository" -DskipTests test-compile
mvn "-Dmaven.repo.local=c:\\Users\\heyunhui\\Desktop\\求职\\面试准备\\AI\\.m2\\repository" -DskipTests spring-boot:run "-Dspring-boot.run.mainClass=com.livepick.RedisTokenExporter" "-Dspring-boot.run.arguments=--limit=200,--csv=load-test-tokens.csv" -Dspring-boot.run.useTestClasspath=true
```

如果你平时直接用 IDEA，最简单的是直接运行 `RedisTokenExporter.main()`，程序参数填：

```text
--limit=200 --csv=load-test-tokens.csv
```

说明：

- `--limit=200`：只导出 200 个 token，正好对应这轮压测
- `--limit=0`：导出全部 token
- `--pattern=login:token:*`：默认就是这个前缀，一般不用改
- 如果 Maven 运行失败，优先用 `redis-cli` 脚本方式；它不依赖项目编译

## GUI 运行

1. 打开 JMeter
2. 导入 `jmeter/seckill-200-users.jmx`
3. 如有需要，修改 `Test Plan -> User Defined Variables` 中的变量：
   - `host`
   - `port`
   - `voucherId`
   - `tokenCsv`
   - `users`
   - `rampUp`
   - `syncTimeoutMs`
4. 先用小规模用户数联调，确认没有 `401`
5. 再恢复到 `200` 用户执行正式压测

## CLI 运行

```powershell
jmeter -n -t jmeter/seckill-200-users.jmx -l jmeter/results/seckill-200-users.jtl
```

如果 token 文件不在默认位置，可覆盖：

```powershell
jmeter -n -t jmeter/seckill-200-users.jmx -l jmeter/results/seckill-200-users.jtl -JtokenCsv=C:/Users/heyunhui/IdeaProjects/LivPick/load-test-tokens.csv
```

如果后续要逐步加压，可覆盖：

```powershell
jmeter -n -t jmeter/seckill-200-users.jmx -l jmeter/results/seckill-200-users.jtl -Jusers=500 -JrampUp=30 -JsyncTimeoutMs=15000
```

## 结果预期

在环境干净且库存为 `100` 的情况下，预期接近：

- 成功下单：`100`
- `库存不足`：`100`
- `401`：`0`
- `不能重复下单`：`0`

如果出现较多 `401`，优先检查 `authorization` 头是否带上，以及 token 是否已经过期。
