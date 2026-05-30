# DB-Cache Flash-Sale 压测报告

## 1. 结论摘要

本次在 `MySQL + Redis` 架构下完成了 4 组更贴近真实电商秒杀特征的高反差压测，正式结果目录为：

```text
target/benchmark/jmeter/flash-sale/db-cache/run-20260530-110438
```

核心结论如下：

1. 在 `5000 用户 + 100/500 库存` 这类“海量需求、极低供给”的场景里，`db-cache` 的主要价值体现在失败请求过滤，而不是成功写单吞吐量无限提升。
2. 两个持续压测场景都稳定跑满 `50000` 次请求，没有出现超卖，最终订单数分别严格等于 `100` 和 `500`。
3. `flash-sustain-5k-100` 与 `flash-sustain-5k-500` 的整体吞吐量都稳定在 `~1.18k req/s`，显著高于此前 `baseline-500` 的 `207.56 req/s`。
4. 持续压测场景的失败几乎全部是业务失败 `库存不足`，没有出现系统连接失败，说明 Redis 在库存耗尽后承担了大部分高并发入口拦截。
5. 两个 `1000` 线程短时爆发场景都出现了 `HttpHostConnectException`，说明在“应用、MySQL、Redis、JMeter 同机”的前提下，单机接入层/本机资源已先于业务逻辑到达极限。
6. MySQL 仍然不是被 INSERT 拖慢，热点仍是：

```text
UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0
```

也就是说，Redis 明显缓解了“无效请求打数据库”的问题，但并没有消除“成功订单路径必须同步竞争同一行库存”的写热点。

## 2. 测试环境

- 分支目标：`db-cache`
- JDK：`11`
- MySQL：`127.0.0.1:3306 / livpick_db_cache`
- Redis：Docker 容器 `livpick-redis-db-cache`，映射端口 `6380`
- JMeter：`C:\Users\heyunhui\Documents\apache-jmeter-5.6.3`
- 应用端口：`8081`

应用启动参数：

```text
--server.port=8081
--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/livpick_db_cache?useSSL=false&serverTimezone=UTC
--spring.redis.host=127.0.0.1
--spring.redis.port=6380
--app.benchmark.enabled=true
--app.benchmark.skip-login-check=true
--app.seckill.consumer.enabled=false
```

## 3. 场景说明

| 场景名 | 类型 | 用户池 | 库存 | 线程数 | 持续时间 |
| --- | --- | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 爆发 | 5000 | 100 | 1000 | 10s |
| `flash-burst-5k-500` | 爆发 | 5000 | 500 | 1000 | 10s |
| `flash-sustain-5k-100` | 持续 | 5000 | 100 | 500 | 60s |
| `flash-sustain-5k-500` | 持续 | 5000 | 500 | 500 | 60s |

## 4. 结果总表

| 场景 | Samples | Success | Failed | QPS | Avg RT(ms) | P95(ms) | P99(ms) | Max(ms) | Error | Final Stock | Final Orders |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 7639 | 100 | 7539 | 782.44 | 551.52 | 902 | 937 | 1002 | 98.69% | 0 | 100 |
| `flash-burst-5k-500` | 3977 | 500 | 3477 | 411.23 | 872.92 | 2314 | 2652 | 3747 | 87.43% | 0 | 500 |
| `flash-sustain-5k-100` | 50000 | 100 | 49900 | 1190.36 | 374.20 | 568 | 634 | 843 | 99.80% | 0 | 100 |
| `flash-sustain-5k-500` | 50000 | 500 | 49500 | 1184.95 | 363.64 | 584 | 681 | 787 | 99.00% | 0 | 500 |

库存一致性结论：

- 四个场景都没有超卖
- 成功订单数与库存完全一致
- 库存耗尽后请求被正确拒绝

## 5. 失败类型分析

### 5.1 持续场景

`flash-sustain-5k-100`：

- 业务成功：`100`
- 业务失败：`49900`
- 系统失败：`0`
- 失败类型：全部为 `库存不足`

`flash-sustain-5k-500`：

- 业务成功：`500`
- 业务失败：`49500`
- 系统失败：`0`
- 失败类型：全部为 `库存不足`

解释：

- 这两组场景最能体现 Redis 的并发承载价值
- 在库存很快耗尽后，海量失败请求主要在 Redis 侧被快速拒绝
- 没有继续把所有无效请求都拖入 MySQL 行锁竞争

### 5.2 爆发场景

`flash-burst-5k-100` 失败分布：

- `库存不足 = 7267`
- `HttpHostConnectException = 272`

`flash-burst-5k-500` 失败分布：

- `库存不足 = 3257`
- `HttpHostConnectException = 220`

解释：

- `1000` 线程短时爆发下，除了正常的业务失败外，还出现了连接失败
- 这说明在“JMeter + 应用 + MySQL + Redis 同机”前提下，接入层或本机资源先触顶
- 因此，这两组场景更适合暴露单机承载极限，而不适合单独作为 Redis 过滤收益的最佳展示样本

## 6. 监控指标与瓶颈分析

### 6.1 Redis 指标

| 场景 | Max Redis CPU(%) | Max Redis Mem(MB) | Max Redis Ops/s | RedisHitsDelta |
| --- | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 20.64 | 9.41 | 3995 | 2725 |
| `flash-burst-5k-500` | 0.20 | 8.36 | 3000 | 1171 |
| `flash-sustain-5k-100` | 20.74 | 10.36 | 4603 | 47805 |
| `flash-sustain-5k-500` | 17.12 | 9.75 | 4690 | 46650 |

观察：

- 两个持续场景的 `RedisHitsDelta` 都在 `4.6w ~ 4.8w` 量级
- 这与库存只成功 `100` 或 `500` 单形成强烈反差
- 说明大量失败请求确实主要被 Redis 入口逻辑承接，而不是继续压向数据库

### 6.2 MySQL 锁等待

| 场景 | RowLockWaitsDelta | RowLockTimeDeltaMs |
| --- | ---: | ---: |
| `flash-burst-5k-100` | 99 | 5271 |
| `flash-burst-5k-500` | 498 | 25840 |
| `flash-sustain-5k-100` | 99 | 4507 |
| `flash-sustain-5k-500` | 321 | 8606 |

对比此前 `db-cache` 基准压测中的 `baseline-500`：

- `QPS = 207.56 req/s`
- `Avg RT = 2344.12 ms`
- `RowLockWaitsDelta = 12265`
- `RowLockTimeDeltaMs = 507923`

解释：

- 在原 `baseline-500` 场景中，大量请求都走到了成功写路径竞争数据库库存行
- 在本次高反差秒杀场景中，库存很快耗尽，后续大量失败请求被 Redis 入口逻辑拦截
- 因此行锁等待次数和锁等待总时长都显著低于 `baseline-500`

这正是 Redis 在秒杀入口“承载并发”的核心价值：

- 它没有让成功下单路径脱离数据库
- 但它大幅减少了无效请求对数据库热点行的持续冲击

### 6.3 热点 SQL

`statement-digest` 显示最重的 SQL 仍然是：

```text
UPDATE tb_seckill_voucher SET stock = stock - ? WHERE (voucher_id = ? AND stock > ?)
```

典型指标：

- `avg_ms ≈ 43 ms`

而订单插入语句：

```text
INSERT INTO tb_voucher_order (...)
```

典型指标：

- `avg_ms ≈ 0.30 ms`

结论：

- 真正的写热点仍然是库存扣减这条单行 UPDATE
- 插入订单不是当前瓶颈
- 如果后续要继续提升成功下单吞吐量，仍然需要异步化落库或进一步削弱同步库存更新竞争

### 6.4 应用 / 系统指标

持续场景中：

- `Max App CPU ≈ 22.88% ~ 24.81%`
- `Max System CPU ≈ 66.10% ~ 82.51%`
- 最低可用内存约 `1764 MB`

解释：

- 500 线程持续压测时，系统层面压力明显，但未出现大量系统连接失败
- 1000 线程爆发时，系统失败已经开始出现

因此，对这台单机环境而言：

- `500` 线程持续压测是更可信的“业务结论场景”
- `1000` 线程更适合作为“单机接入极限暴露场景”

## 7. 与已有 db-cache 结果的关系

此前 `db-cache` 基准报告已经证明两点：

1. 成功写单路径的瓶颈在 MySQL 热点库存行，不在 Redis
2. 读缓存场景的吞吐量可以达到：
   - `cache-hit-200 = 6583.55 req/s`
   - `cache-penetration-200 = 8919.33 req/s`
   - `cache-breakdown-200 = 8401.67 req/s`

本次 `flash-sale` 结果进一步补充了第三点：

3. 当业务场景改成“极低库存 + 海量请求”后，Redis 不一定提升成功订单吞吐量上限，但能明显提升整体入口吞吐量，并把数据库锁竞争限制在少量成功单上。

补充说明：

- 本次 `flash-sale` 套件压测的是秒杀写路径，不是商户详情读缓存路径
- 因此场景目录中的 `benchmark-metrics-delta.json` 里 `cacheHit / nullHit / cacheMiss` 基本为 `0` 属于正常现象
- 这并不表示 Redis 没起作用，而是说明这里更适合看 `RedisHitsDelta`、`Redis Ops/s`、`库存不足` 返回比例以及 MySQL 锁等待下降
- 如果要分析“缓存空值、逻辑过期、热点 Key 重建”等缓存命中表现，应以原报告中的 `cache-hit-200`、`cache-penetration-200`、`cache-breakdown-200` 为准

因此，`db-cache` 这一版架构的能力边界可以概括为：

- 读流量：Redis 收益非常显著
- 写成功路径：仍受 MySQL 单行库存更新限制
- 高反差秒杀入口：Redis 对失败请求过滤和数据库减压很有价值

## 8. 最终结论

如果你的问题是“为什么 `db-cache` 和 `mysql-only` 在普通基准写场景下差不多”，本次结果给出了更完整的解释：

1. 在所有请求都持续竞争成功写路径时，MySQL 热点库存更新才是主瓶颈，Redis 无法凭空把成功写吞吐量抬高很多。
2. 在更符合真实秒杀特征的“库存极少、需求极大”场景下，Redis 的价值会明显体现为：
   - 快速拒绝无效请求
   - 降低 MySQL 行锁等待
   - 提高整体入口吞吐量
   - 保持零超卖
3. 但如果想继续显著提升“成功订单”的吞吐能力，下一阶段仍然需要引入消息队列或其他异步落库方案，把成功路径从同步数据库竞争中进一步解耦。

## 9. 报告对应产物

本报告对应的原始产物位于：

```text
target/benchmark/jmeter/flash-sale/db-cache/run-20260530-110438/
```

建议优先查看：

- `aggregate-summary.csv`
- `flash-sustain-5k-100/flash-sustain-5k-100.summary.json`
- `flash-sustain-5k-500/flash-sustain-5k-500.summary.json`
- `flash-sustain-5k-100/statement-digest.txt`
- `flash-sustain-5k-100/redis-monitor.csv`
- `flash-burst-5k-100/flash-burst-5k-100.summary.json`
