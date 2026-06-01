# 压测与架构总结报告

## 1. 结论先行

这轮对比需要分成两个层次看，否则很容易把结论说偏。

### 1.1 如果只看当前本机单节点压测的“入口吞吐”

结论是：

`db-cache > db-cache-mq > mysql-only`

原因很直接：

- `db-cache` 在高反差秒杀场景下，Redis 前置过滤最充分，当前同机单 Redis + 单 Kafka broker + 单 partition 的本地环境里，入口 QPS 最好。
- `db-cache-mq` 虽然完成了异步削峰，但当前消费端仍是单 topic 单 partition 单通道，Kafka 的并行优势没有真正展开，所以入口吞吐没有跑赢 `db-cache`。
- `mysql-only` 没有前置过滤，失败请求会继续打到 MySQL 热点库存行，整体最弱。

### 1.2 如果看“综合工程能力和项目成熟度”

结论是：

`db-cache-mq > db-cache > mysql-only`

原因不是单看一项 QPS，而是看完整系统能力：

- `mysql-only` 只有最基本的同步落库能力，热点竞争最重。
- `db-cache` 解决了秒杀入口过滤和缓存相关问题，但成功请求仍是同步写库，业务闭环能力有限。
- `db-cache-mq` 在 `db-cache` 的基础上补齐了异步削峰、最终落库、超时关单、库存回补、支付并发控制、补偿重试和可观测性，是当前三种架构里最完整的终态方案。

所以这份总结报告的正式结论写法是：

- 纯本机单节点压测吞吐表现：`db-cache > db-cache-mq > mysql-only`
- 综合工程能力与架构终态：`db-cache-mq > db-cache > mysql-only`

## 2. 对比口径

本报告引用三份专题报告中的正式结果：

- [mysql-only.md](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/benchmark-final/reports/mysql-only.md)
- [db-cache.md](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/benchmark-final/reports/db-cache.md)
- [db-cache-mq.md](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/benchmark-final/reports/db-cache-mq.md)

需要特别说明：

- `mysql-only` 与 `db-cache` 的结果来自既有历史正式报告。
- `db-cache-mq` 是本轮在独立 schema `livpick_db_cache_mq` 上补跑。
- 本轮 `db-cache-mq` 使用的是单机、单 Redis、单 Kafka broker、单 partition。
- 这个部署方式会压低 Kafka 架构的并行收益，因此更适合验证“异步削峰闭环是否正确”，不适合把它当作 Kafka 横向扩展能力的上限。

## 2.1 本轮压测使用的技术栈

本轮 benchmark 不是只靠 JMeter 单点压接口，而是由以下几部分组合完成：

- 压测流量生成：`JMeter 5.6.3`
- 场景编排与结果汇总：`PowerShell` runner 脚本
- 应用侧观测：业务内 `BenchmarkMetricsService` 探针 + `/benchmark/metrics` 系列接口
- 中间件状态采样：
  - `docker exec redis-cli`
  - `docker exec kafka-consumer-groups.sh`
  - `mysql` 命令行查询
- 运行环境：
  - `Java 11`
  - `MySQL`
  - `Redis`
  - `Kafka`
  - `Redisson`

对应实现位置包括：

- [`benchmark-final/suites/db-cache-mq/standard/run-jmeter-benchmark.ps1`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\benchmark-final\suites\db-cache-mq\standard\run-jmeter-benchmark.ps1)
- [`benchmark-final/suites/db-cache-mq/flash-sale/run-jmeter-benchmark.ps1`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\benchmark-final\suites\db-cache-mq\flash-sale\run-jmeter-benchmark.ps1)
- [`src/main/java/com/livepick/service/benchmark/BenchmarkMetricsService.java`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\src\main\java\com\livepick\service\benchmark\BenchmarkMetricsService.java)

因此当前压测方案可以概括为：

`JMeter + PowerShell 编排 + 业务内埋点 + MySQL/Redis/Kafka 命令行采样`

## 2.2 当前系统指标监控是怎么实现的

是的，当前这套 benchmark 指标采集，本质上是通过**直接在业务代码中插入轻量探针**来实现的。

具体方式是：

- 在秒杀、缓存、关单、稳定性相关链路中埋入计数器与延迟采样逻辑
- 由 `BenchmarkMetricsService` 统一维护内存中的指标快照
- 再通过 `/benchmark/metrics` 和 `/benchmark/admin/**` 接口对外暴露

这意味着当前方案更偏向：

- `benchmark-only` 观测方案
- 面向实验和压测结论服务
- 不是一套完整的通用监控平台

## 2.3 相较 Prometheus 的优缺点

### 当前业务内探针方案的优点

#### 1. 实现成本低，接入快

不需要额外部署 Prometheus、Exporter、Grafana，只要在业务关键路径加计数器和采样逻辑，就可以很快形成一套可用于压测的数据面。

#### 2. 业务语义更强

Prometheus 更适合采集通用系统指标，比如：

- CPU
- 内存
- JVM
- HTTP QPS

但像下面这些强业务语义指标：

- `luaStockRejected`
- `pendingRegistered`
- `consumerCreated`
- `timeoutDelayQueueTriggered`
- `consumerPauseAccepted`

直接在业务里埋点更容易表达，也更适合支撑秒杀专题压测结论。

#### 3. 更适合做实验型对照

这套方案支持 benchmark-only 开关、模式切换和实验控制，比如：

- `cache-penetration` 开 / 关
- `timeout-mode` 切换
- `consumer-pause` 演练

对你当前这种“以项目压测和架构分析为目标”的场景，其实很合适。

### 当前业务内探针方案的缺点

#### 1. 不够标准化

这套指标体系是项目内自定义的，不具备 Prometheus 那种标准化采集、拉取、存储和查询生态。

#### 2. 缺少系统级全局视角

当前方案虽然能看到业务关键指标，但对下面这些系统层信息不如 Prometheus 完整：

- JVM GC
- 线程池状态
- 主机 CPU / 内存 / 磁盘
- Redis/Kafka/MySQL 的完整运行指标

所以它更像“业务专题观测”，而不是“系统级统一监控”。

#### 3. 数据持久化和可视化能力弱

当前指标是内存态快照，通过接口临时读取，更适合单轮压测采样和结果汇总。  
而 Prometheus 天然更适合：

- 长时间序列存储
- 多次实验横向对比
- 告警
- Grafana 可视化

#### 4. 对代码侵入更强

Prometheus 通常通过标准埋点或成熟 exporter 接入，而当前方案是你直接在业务代码里插入自定义探针。  
这会带来：

- 代码耦合更高
- 维护口径要靠人工约束
- benchmark 逻辑和业务逻辑更容易缠在一起

## 2.4 为什么本项目当前没有优先上 Prometheus

这不是因为 Prometheus 不好，而是因为当前目标更偏向：

- 产出可用于简历和面试的专题压测数据
- 聚焦秒杀业务链路的关键指标
- 在本机单机环境下快速完成架构对比实验

在这个目标下，业务内探针方案更轻、更直接，也更容易快速得到“Lua 拦截了多少请求、Kafka 最终创建了多少订单、关单是延迟队列触发还是 fallback 触发”这类业务结论。

如果后续要把这个项目继续往“更像生产监控体系”方向打磨，那么最合理的升级路线是：

1. 保留当前业务语义探针
2. 补充 Micrometer + Prometheus
3. 再接 Grafana 做统一可视化

这样既不丢秒杀专题指标，也能补上系统层监控能力。

## 3. standard 场景对比

### 3.1 常规成功写路径

| 指标 | mysql-only | db-cache | db-cache-mq |
| --- | ---: | ---: | ---: |
| `baseline-100 QPS` | 219.02 | 199.33 | 约 202.44 |
| `baseline-500 QPS` | 214.58 | 207.56 | 本轮不作为主结论 |
| `baseline-500 Avg RT(ms)` | 2265.56 | 2344.12 | 本轮不作为主结论 |

结论：

- 三种架构在“大库存、绝大部分请求都最终要成功落库”的场景下，本质都要回到数据库写热点竞争。
- `db-cache` 相比 `mysql-only` 在这个场景没有本质跃迁。
- `db-cache-mq` 在这个场景下会把同步写库压力改造成异步排队压力，所以不适合作为它的主卖点。

换句话说，`standard` 更像“数据库写热点对比”，不是最能体现 `db-cache-mq` 价值的场景。

## 4. flash-sale 场景对比

这部分才是三种架构差异最明显、也最适合写进简历的部分。

### 4.1 sustain 场景

| 指标 | mysql-only | db-cache | db-cache-mq |
| --- | ---: | ---: | ---: |
| `flash-sustain-5k-100 QPS` | 577.44 | 1190.36 | 584.75 |
| `flash-sustain-5k-100 Avg RT(ms)` | 804.97 | 374.20 | 805.22 |
| `flash-sustain-5k-100 Final Orders` | 100 | 100 | 100 |
| `flash-sustain-5k-500 QPS` | 574.55 | 1184.95 | 577.95 |
| `flash-sustain-5k-500 Avg RT(ms)` | 813.23 | 363.64 | 807.88 |
| `flash-sustain-5k-500 Final Orders` | 500 | 500 | 500 |

`db-cache-mq` 额外可观测结果：

| 指标 | 5k-100 | 5k-500 |
| --- | ---: | ---: |
| `ApiAccepted` | 100 | 500 |
| `ConsumerCreated` | 100 | 500 |
| `luaStockRejected` | 34816 | 33990 |
| `FinalStock` | 0 | 0 |
| `Drained` | true | true |

结论：

- `mysql-only` 和 `db-cache-mq` 在当前单节点本地环境下，入口吞吐接近，都在 `~578 req/s` 左右。
- `db-cache` 在 sustain 场景下入口吞吐明显更高，约为另外两者的 `2x`。
- 但 `db-cache-mq` 的一个重要特点是：真正进入异步成功链路的请求量严格收敛到了库存量级 `100/500`，并且最终全部成功落库，说明异步削峰闭环是成立的。

### 4.2 burst 场景

| 指标 | mysql-only | db-cache | db-cache-mq |
| --- | ---: | ---: | ---: |
| `flash-burst-5k-100 QPS` | 438.54 | 782.44 | 555.48 |
| `flash-burst-5k-100 Avg RT(ms)` | 1167.50 | 551.52 | 1142.99 |
| `flash-burst-5k-100 Final Orders` | 100 | 100 | 100 |
| `flash-burst-5k-500 QPS` | 410.70 | 411.23 | 515.50 |
| `flash-burst-5k-500 Avg RT(ms)` | 1304.63 | 872.92 | 1278.85 |
| `flash-burst-5k-500 Final Orders` | 500 | 500 | 500 |

`db-cache-mq` 额外可观测结果：

| 指标 | 5k-100 | 5k-500 |
| --- | ---: | ---: |
| `ApiAccepted` | 100 | 500 |
| `ConsumerCreated` | 100 | 500 |
| `luaStockRejected` | 5112 | 4400 |
| `FinalStock` | 0 | 0 |
| `Drained` | true | true |

结论：

- 在短时冲击场景下，`db-cache` 依然表现出最强的本机入口承压能力，尤其是 `flash-burst-5k-100`。
- `db-cache-mq` 在 `flash-burst-5k-500` 上已经跑赢了 `mysql-only` 和 `db-cache` 的入口 QPS，但整体还没有稳定压过 `db-cache`。
- `db-cache-mq` 的价值更体现在“冲击后仍能保证最终排空和最终一致”，而不是当前本地单 partition 下的瞬时入口极限。

## 4.3 缓存穿透专项

这组实验只使用纯非法 `shopId=99999999` 流量，目的是单独量化 `RBloomFilter + 缓存空值` 对缓存穿透的治理效果。

| 场景 | Samples | QPS | P95(ms) | DB Fallback | Null Hit | Bloom Rejected | Redis Query |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `cache-penetration-off-200` | 130781 | 2185.58 | 114 | 26 | 130755 | 0 | 130781 |
| `cache-penetration-bloom-null-200` | 171597 | 2867.79 | 91 | 0 | 0 | 171597 | 0 |

结论：

- `BLOOM_NULL` 将非法请求场景下的 `DB fallback` 从 `26` 次压低到 `0`，数据库无效回源下降 `100%`。
- 查询 `P95` 从 `114ms` 压低到 `91ms`，吞吐从 `2185.58 req/s` 提升到 `2867.79 req/s`。
- 这说明布隆过滤器不仅拦住了无效回源，也减少了 Redis 空值写入与重复读取链路上的额外开销。

## 4.4 关单时效性专项

这组实验以 `100 单 / 15 秒` 为统一口径，对比纯 `Spring Task` 扫描与 `Redisson` 延迟队列主路径方案的差异。

| 模式 | TimeoutClosed | Delay Queue Triggered | Fallback Triggered | P50(ms) | P95(ms) | Max(ms) | FinalCancelledOrders | FinalStock |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `FALLBACK_ONLY` | 100 | 0 | 100 | 3322 | 4055 | 4116 | 100 | 1000 |
| `DELAY_QUEUE_FALLBACK` | 100 | 100 | 0 | 26 | 533 | 571 | 100 | 1000 |

结论：

- `DELAY_QUEUE_FALLBACK` 将未支付订单自动关单 `P95` 延迟从 `4055ms` 缩短到 `533ms`，时效提升约 `86.9%`。
- `P50` 从 `3322ms` 降到 `26ms`，说明延迟队列主路径能够更贴近订单到期时刻触发关单。
- 本轮结果中 `DelayQueueTriggered=100` 且 `FallbackTriggered=0`，说明延迟队列主路径已完整承接超时处理，`Spring Task` 主要承担兜底角色。

## 5. 为什么 db-cache-mq 仍然可以作为最终架构

这是这轮最容易被误解的地方。

如果只看 `flash-sale` 入口 QPS，当前结果并不能证明 `db-cache-mq` 比 `db-cache` 更快。  
但如果把系统设计目标放回完整业务闭环，`db-cache-mq` 仍然是三者中最强的架构终态，原因有四点。

### 5.1 它解决的是“成功请求如何异步消化”

`db-cache` 的核心收益是把失败请求挡在 Redis 前面。  
但一旦请求通过校验，成功链路仍然是同步写库。

`db-cache-mq` 把这条成功链路改造成：

- Redis/Lua 预占资格
- Kafka 异步削峰
- 消费端最终落库
- 超时关单后库存和资格回补

也就是说，`db-cache` 解决的是“失败请求过滤”，`db-cache-mq` 解决的是“成功请求异步消化和业务闭环”。

### 5.2 它补齐了工程闭环能力

`db-cache-mq` 当前已经具备而另外两种架构没有完整闭环的能力：

- Kafka 异步落库
- pending-send 补偿重试
- 取消后库存和购买资格回补
- 取消后复用原订单再次下单
- Redisson 延迟队列 + Spring Task 兜底关单
- 支付与关单并发下的乐观锁控制
- benchmark 控制面与链路指标暴露

这意味着它不只是“多了个 MQ”，而是把系统从“能扛住一波请求”推进到了“能正确地扛住、落库、补偿、回补、恢复”。

### 5.3 当前本机部署压制了 Kafka 的优势

这轮 `db-cache-mq` 的结果是在：

- 单机
- 单 Kafka broker
- 单 partition
- 单消费通道

的前提下得到的。

在这个配置里，Kafka 的横向并行能力根本没有展开，所以它更像“把同步写库改成了单通道异步写库”。  
因此当前结果更适合证明：

- 架构闭环正确
- 异步削峰成立
- 最终一致性成立

而不是证明“本地单机下 Kafka 一定比 Redis 直挡更高吞吐”。

### 5.4 它是更合理的项目终态

从项目演进逻辑看：

- `mysql-only` 是起点
- `db-cache` 是入口过滤和缓存优化阶段
- `db-cache-mq` 是在前者基础上进一步补齐异步化与业务恢复能力的终态

所以最终排序写成：

`db-cache-mq > db-cache > mysql-only`

成立的前提不是“当前所有单项压测数字都第一”，而是“综合工程能力、业务闭环完整性、可扩展方向和项目成熟度最强”。

## 6. 最终建议写法

### 6.1 报告级结论

- `mysql-only`：结构最简单，但热点库存行竞争最重，失败请求会持续冲击 MySQL。
- `db-cache`：在高反差秒杀场景中显著优于 `mysql-only`，入口吞吐和数据库减压效果最好，是最强的本机单节点入口过滤方案。
- `db-cache-mq`：在当前单 broker 单 partition 本地环境下，入口吞吐未全面超越 `db-cache`，但它补齐了异步削峰、最终落库、关单回补、补偿重试和可观测性，是综合工程能力最完整的最终架构。

### 6.2 简历级结论

可以这样写，而不要写成“MQ 一定让本机入口 QPS 超过所有架构”：

- “秒杀系统从 `MySQL-only` 演进到 `Redis 前置过滤`，再演进到 `Redis + Kafka 异步削峰`，逐步完成了入口过滤、异步落库、超时关单、库存回补和补偿重试等完整业务闭环。”
- “压测结果表明，`db-cache` 在高反差秒杀场景下可将入口吞吐提升至 `mysql-only` 的约 `2x`，并将数据库行锁等待压缩到原来的 `1%` 量级。”
- “在此基础上继续引入 Kafka、延迟队列和补偿机制后，系统具备了更完整的异步削峰和最终一致性能力，使成功请求处理链路从同步写库演进为可恢复的异步闭环。”

## 7. 最终排序

### 7.1 纯本机单节点入口吞吐排序

`db-cache > db-cache-mq > mysql-only`

### 7.2 综合工程能力与架构终态排序

`db-cache-mq > db-cache > mysql-only`

这也是这份最终总结报告采用的正式结论。

## 8. db-cache-mq 的瓶颈与后续优化方向

这部分是对当前压测现象的进一步解释，也是后续继续优化 `db-cache-mq` 时最值得关注的地方。

### 8.1 当前 Kafka 存在的核心问题

#### 1. 单 broker + 单 partition，消费天然单通道

当前 `db-cache-mq` 使用的是单机、单 broker、`1 partition` 的 Kafka 配置。  
在这个前提下，Kafka 更像“单通道异步缓冲层”，而不是“并行消费引擎”，所以它无法充分释放 MQ 架构的并发潜力。

#### 2. 生产端仍然同步等待 ack

当前秒杀发送链路虽然已经接入 Kafka，但生产端仍然在主链路中同步等待发送结果返回。  
这意味着接口线程并没有完全从“等待后端确认”里解放出来，接口响应时间仍然会受到 Kafka 网络和 broker ack 的影响。

#### 3. 消费端是重事务串行处理

每条成功消息在消费端都要执行完整事务：

- 分布式锁
- 查历史订单
- 扣库存
- 写订单
- 投递超时关单消息

这会使得 Kafka 后面的处理吞吐，最终仍然受 MySQL 和单条事务成本约束。

### 8.2 为什么这不影响它作为最终架构

这些问题说明的是：

- 当前本机配置还没有把 Kafka 的并行能力真正跑出来
- 但不代表 `db-cache-mq` 的架构方向不对

它依然解决了 `db-cache` 没有解决的事情：

- 成功请求异步消化
- 失败补偿
- 超时关单
- 库存和资格回补
- 状态恢复和最终一致性

所以它仍然是更完整的工程终态。

### 8.3 后续优化建议

如果继续做下一轮性能优化，建议优先从以下方向入手：

1. 把 Kafka 发送改成真正异步，去掉主链路同步等待。
2. 把 topic 从 `1 partition` 提升到 `2/4 partition`，做敏感性对比。
3. 配套提高消费端并发，不让成功请求全部挤在单通道里。
4. 精简消费者单条事务路径，压缩查订单、回写和超时消息投递的总成本。
5. benchmark 时降低 debug 日志级别，避免高频日志放大消费开销。

换句话说，当前 `db-cache-mq` 的重点不是继续优化 Redis 入口，而是继续优化“成功请求的异步后半段链路”。
