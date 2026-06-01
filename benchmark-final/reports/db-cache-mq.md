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

## 与其他架构的对比口径

完整横向结论见：

- [architecture-comparison.md](/C:/Users/heyunhui/IdeaProjects/LivPick-db-cache-mq/interview/architecture-comparison.md)

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
- 本轮正式数字尚未补跑完成，因此这里暂不写入未验证数值。

预期结论模板：

- “布隆过滤器 + 缓存空值联合方案使非法请求场景下的 DB 回源显著下降，同时保持较稳定的 P95 响应时间。”

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
- 本轮正式 A/B 数据尚未补跑完成，因此这里不提前写结论数字。

预期结论模板：

- “Redisson 延迟队列 + 低频 Spring Task 兜底方案可缩短未支付订单自动关单的尾部延迟。”

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
