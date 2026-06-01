# DB-Cache-MQ 压测报告

## 实验口径

- 本轮只补跑 `db-cache-mq`，`mysql-only` 和 `db-cache` 继续引用既有历史同机报告作为对比基线。
- `db-cache-mq` 的正式结论以高反差秒杀场景为主，不以大库存 `baseline` 作为核心卖点。
- 统一环境：
  - JDK 11
  - MySQL: `livpick_db_cache_mq`
  - Redis: 单节点 `livpick-redis`
  - Kafka: 单 broker、`1 partition`、`livpick-kafka`
  - benchmark 身份注入：`X-Benchmark-User-Id`
- 由于本轮使用的是单机、单 broker、单 partition，本报告更适合证明“异步削峰闭环正确且可观测”，不把它当作 Kafka 横向扩展上限。

## 主结论

### 1. 秒杀主结论优先看 `flash-sale`

`db-cache-mq` 最值得写进简历的，不是“大库存下所有成功单最终都异步落库”，而是高反差秒杀场景下：

- Redis + Lua 在入口快速筛掉绝大多数无效请求。
- 真正进入 Kafka 并最终落库的请求量，被严格收敛到库存量级。
- 即使存在异步链路，也能保证库存与订单最终一致，且 backlog 最终可排空。

正式主场景如下：

- `flash-burst-5k-100`
- `flash-burst-5k-500`
- `flash-sustain-5k-100`
- `flash-sustain-5k-500`

### 2. 大库存 `baseline` 只作为辅助说明

大库存 `baseline` 更像“把同步写库压力转成异步排队压力”的对照场景，不适合拿来证明 `db-cache-mq` 一定优于 `db-cache`。  
因此本报告的核心卖点不放在 `baseline`，而放在高反差秒杀下的入口削峰和最终一致性。

## Flash Sale 正式结果

### sustain 场景

| 场景 | Samples | 入口 QPS | 平均响应(ms) | 成功受理数 | 最终建单数 | 最终库存 | Lua 前置拒绝数 | Drain 完成 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `flash-sustain-5k-100` | 34916 | 584.75 | 805.22 | 100 | 100 | 0 | 34816 | `true` |
| `flash-sustain-5k-500` | 34490 | 577.95 | 807.88 | 500 | 500 | 0 | 33990 | `true` |

结论：

- 在 `5k` 并发、库存仅 `100/500` 的高反差场景下，真正进入成功链路的请求被严格压缩到库存量级。
- `3.4w+` 级别的总请求中，绝大多数请求在 Redis + Lua 层被快速拒绝，没有继续把数据库写路径打满。
- 最终 `FinalStock=0` 且 `FinalOrders=库存量`，说明异步落库没有破坏正确性。

### burst 场景

| 场景 | Samples | 入口 QPS | 平均响应(ms) | 成功受理数 | 最终建单数 | 最终库存 | Lua 前置拒绝数 | Drain 完成 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `flash-burst-5k-100` | 5442 | 555.48 | 1142.99 | 100 | 100 | 0 | 5112 | `true` |
| `flash-burst-5k-500` | 5073 | 515.50 | 1278.85 | 500 | 500 | 0 | 4400 | `true` |

结论：

- 在短时洪峰冲击下，系统仍然能把成功请求量稳定控制在库存范围内。
- 即使入口流量突刺，最终也能保证订单和库存一致，并完成异步链路排空。
- 这组数据更适合证明“抗突刺能力和系统韧性”，而不是单纯追求本机极限 QPS。

## 瓶颈分析与核心优化点

### 1. 当前压测下的主要瓶颈

从这轮结果看，`db-cache-mq` 没有在本机单节点环境下全面跑赢 `db-cache`，核心原因不是 Redis 前置过滤失效，而是成功链路被压在了更重的异步消费和落库路径上。

当前瓶颈主要有四层：

#### 1.1 Kafka 侧瓶颈：单 broker + 单 partition + 单消费通道

本轮配置是：

- 单机
- 单 Kafka broker
- `1 partition`
- 单 consumer group 消费通道

这意味着所有成功请求最终都会收敛到一条 Kafka 消费流水线。  
所以 Kafka 在这里更像“单通道异步落库缓冲层”，而不是“多分区并行处理引擎”。这也是为什么当前环境下 `db-cache-mq` 的入口吞吐没有稳定超过 `db-cache`。

#### 1.2 消费端瓶颈：单条消息对应完整事务

当前每条成功消息进入消费端后，至少会经历以下步骤：

- 获取分布式锁
- 查询该用户该券是否已有订单
- 扣减数据库库存
- 新建订单或重激活已取消订单
- 清理可复用订单标记
- 投递超时关单消息

也就是说，`db-cache-mq` 并没有减少“成功请求最终需要完成的数据库工作量”，只是把这部分工作从前台请求线程转移到了后台消费者线程。  
因此在大库存场景下，系统会表现为“入口先接住，后台慢慢消化”。

#### 1.3 数据库侧瓶颈：成功单最终仍受 MySQL 写路径约束

即使入口层已经通过 Redis + Lua 挡掉了大量无效请求，只要请求最终成功，它还是要回到 MySQL 完成：

- 库存扣减
- 订单写入
- 支付 / 关单状态更新

所以 `db-cache-mq` 的本质不是“消灭数据库写压力”，而是“把数据库写压力变成可异步、可排队、可恢复的后台链路”。  
这解释了为什么它在高反差秒杀场景下正确性和韧性很好，但在当前单机环境下 raw QPS 未必优于 `db-cache`。

#### 1.4 工程配置瓶颈：当前日志级别会额外放大消费成本

当前 [`application.yaml`](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/src/main/resources/application.yaml) 中：

- `logging.level.com.livepick = debug`

而消费者 [`SeckillOrderConsumer.java`](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/src/main/java/com/livepick/mq/consumer/SeckillOrderConsumer.java) 会在消费路径打印 debug 日志。  
在高频消费场景下，这会增加额外的 I/O 和序列化成本，进一步压低消费吞吐。

### 2. 这个架构真正优化了什么

`db-cache-mq` 的核心优化点，不是简单理解成“用了 MQ，所以本机 QPS 一定更高”，而是下面三点。

#### 2.1 第一层优化：Redis + Lua 把绝大多数失败请求挡在入口

这是这套架构最直接、也最有效的优化点。

从正式结果看：

- `flash-sustain-5k-100` 中，总请求 `34916`，真正成功受理只有 `100`
- `flash-sustain-5k-500` 中，总请求 `34490`，真正成功受理只有 `500`

也就是说，绝大多数请求在 Lua 层就完成了“库存不足 / 重复下单”的快速拒绝，没有继续打到数据库。  
这一步才是秒杀场景下最关键的减压动作。

#### 2.2 第二层优化：Kafka 把成功请求改造成可恢复的异步链路

`db-cache` 解决的是“失败请求不要打到数据库”。  
`db-cache-mq` 在此基础上继续解决的是“成功请求怎么消化”。

它把成功链路改造成：

- Redis 预占库存和资格
- Kafka 异步投递
- 消费端最终落库
- 超时关单后库存与资格回补
- pending-send 重试和补偿兜底

因此 MQ 的核心价值是：

- 解耦前台接口和后台落库
- 让成功请求处理具备排队、补偿、恢复能力
- 让系统从“同步写库”演进到“异步闭环”

换句话说，MQ 优化的是系统形态和恢复能力，不只是某个瞬时 QPS 数字。

#### 2.3 第三层优化：状态机和补偿闭环被补完整了

这轮 `db-cache-mq` 真正补出的工程价值包括：

- 取消后库存恢复
- 取消后购买资格恢复
- 复用已取消订单再次抢购
- 支付与关单并发下的乐观锁控制
- pending-send 失败补偿
- 延迟队列 + fallback 兜底关单
- benchmark 指标和 drain 状态观测

这意味着它不只是“比 `db-cache` 多了个 MQ”，而是把成功请求的生命周期真正做成了闭环。

### 3. 结论怎么写最准确

最准确的结论不是：

- “`db-cache-mq` 在本机压测下吞吐一定优于 `db-cache`”

而应该是：

- 当前单机、单 broker、单 partition 环境下，`db-cache` 的纯入口吞吐更强。
- `db-cache-mq` 的主要瓶颈在单通道 Kafka 消费和最终 MySQL 落库。
- `db-cache-mq` 的核心优化价值在于：通过 Redis + Lua 过滤绝大多数失败请求，并把少量成功请求改造成可异步、可补偿、可恢复的最终一致性闭环。
- 因此它更适合作为“工程终态最完整”的架构，而不是“本机单节点极限吞吐最高”的架构。

## 与其他架构的对比口径

完整横向结论见：

- [benchmark-and-architecture-summary.md](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/interview/benchmark-and-architecture-summary.md)

这里保留本架构最重要的结论：

- 如果只看当前本机单节点入口吞吐，`db-cache` 仍然更强。
- 如果看完整工程闭环，`db-cache-mq` 具备异步削峰、补偿重试、超时关单、库存回补和可观测性，是三种架构里最完整的终态方案。

因此本项目中对 `db-cache-mq` 的正确表述应该是：

- 它不一定在当前单机单 partition 压测下拥有最高入口 QPS。
- 但它最适合支撑“秒杀系统从同步写库演进到可恢复的异步闭环”这一项目亮点。

## 缓存穿透专项

对照场景规划如下：

- `cache-penetration-off-200`
- `cache-penetration-bloom-null-200`

重点指标：

- `dbFallback`
- `cacheNullHit`
- `bloomRejected`
- `latencyP95Ms`

当前状态：

- benchmark 控制面和指标埋点已补齐。
- 本轮已按“纯非法 ID”口径完成正式补跑。

正式结果：

| 场景 | Samples | QPS | P95(ms) | DB Fallback | Null Hit | Bloom Rejected | Redis Query |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `cache-penetration-off-200` | 130781 | 2185.58 | 114 | 26 | 130755 | 0 | 130781 |
| `cache-penetration-bloom-null-200` | 171597 | 2867.79 | 91 | 0 | 0 | 171597 | 0 |

结论：

- 在纯非法 ID 请求场景下，`BLOOM_NULL` 将 `DB fallback` 从 `26` 次降到 `0`，DB 回源下降 `100%`。
- 同时该方案把 `P95` 从 `114ms` 压低到 `91ms`，吞吐从 `2185.58 req/s` 提升到 `2867.79 req/s`。
- 这说明布隆过滤器不仅减少了数据库无效查询，也减少了 Redis 空值写入与后续读取路径上的额外开销。

## 关单时效性专项

对照模式规划如下：

- `FALLBACK_ONLY`
- `DELAY_QUEUE_FALLBACK`

重点指标：

- `timeout.closeLagP50Ms`
- `timeout.closeLagP95Ms`
- `timeout.closeLagMaxMs`
- `timeout.delayQueueTriggered`
- `timeout.fallbackTriggered`

当前状态：

- 切换开关、指标埋点和脚本入口已补齐。
- 本轮已按 `100 单 / 15 秒` 口径完成正式 A/B 补跑。

正式结果：

| 模式 | TimeoutClosed | Delay Queue Triggered | Fallback Triggered | P50(ms) | P95(ms) | Max(ms) | FinalCancelledOrders | FinalStock |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `FALLBACK_ONLY` | 100 | 0 | 100 | 3322 | 4055 | 4116 | 100 | 1000 |
| `DELAY_QUEUE_FALLBACK` | 100 | 100 | 0 | 26 | 533 | 571 | 100 | 1000 |

结论：

- `DELAY_QUEUE_FALLBACK` 将未支付订单自动关单 `P95` 延迟从 `4055ms` 降低到 `533ms`，缩短约 `86.9%`。
- `P50` 从 `3322ms` 降低到 `26ms`，说明延迟队列主路径能够更接近订单到期点触发关单。
- 本轮正式结果中 `DelayQueueTriggered=100` 且 `FallbackTriggered=0`，说明延迟队列主路径已完全承接超时处理；`Spring Task` 在该轮作为兜底未介入。

## 稳定性与可恢复性

本架构额外具备以下可观测和可恢复能力：

- Kafka 异步削峰
- pending-send 补偿重试
- consumer 暂停/恢复实验开关
- backlog 观察与 drain 状态查询
- 超时关单后的库存与资格回补

这部分的价值不在于宣称生产级 SLA，而在于说明：

- 成功请求不再全部同步压在数据库事务路径上。
- 出现短时积压后，系统可以继续受理入口请求，并在恢复后排空消息。
- 最终库存、订单、资格状态可以回到一致状态。

## 简历可直接使用的量化表述

- “在高并发秒杀场景中，引入 Redis + Kafka 异步削峰后，在 `5k` 级并发、库存仅 `100/500` 的高反差场景下，将成功请求量稳定收敛到库存量级，并保证库存与订单最终一致。”
- “`flash-sustain-5k-100/500` 场景下，总请求量均在 `3.4w+`，最终只有 `100/500` 个请求进入成功链路，其余请求在 Redis + Lua 层完成快速拦截，显著降低了数据库热点写竞争。”
- “在 `flash-burst-5k-100/500` 瞬时冲击下，系统仍能保证异步链路最终排空，且最终库存归零、订单数与库存严格一致，验证了秒杀削峰和最终一致性闭环。”
- “布隆过滤器 + 缓存空值方案使纯非法请求场景下数据库回源从 `26` 次降至 `0`，同时将查询 `P95` 从 `114ms` 压低到 `91ms`。”
- “基于 `Redisson` 延迟队列 + `Spring Task` 兜底的关单方案，将未支付订单自动关单 `P95` 延迟从 `4055ms` 缩短至 `533ms`。”
