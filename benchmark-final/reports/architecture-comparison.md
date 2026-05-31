# 架构横向比较

## 1. 摘要

本文统一比较当前已完成的两种架构：

- `mysql-only`
- `db-cache`

对应专题报告：

- [mysql-only.md](C:/Users/heyunhui/IdeaProjects/LivPick/benchmark-final/reports/mysql-only.md:1)
- [db-cache.md](C:/Users/heyunhui/IdeaProjects/LivPick/benchmark-final/reports/db-cache.md:1)

当前结论：

1. 在 standard 成功写路径上，`mysql-only` 与 `db-cache` 几乎没有本质差异，吞吐上限都稳定在 `~200 req/s`。
2. 在 flash-sale 高反差场景中，`db-cache` 的入口吞吐约为 `mysql-only` 的 `2x`，而且显著降低了数据库锁等待。
3. 两种架构在库存一致性上都正确，但 `db-cache` 以更低的数据库代价完成了相同的成功订单数。

说明：

- 本文对照所用数字来自两个专题报告中的正式结果。
- 当前工作区保留了 `mysql-only` 的最新 `target/benchmark/...` 产物；`db-cache` 的历史 `target` 目录当前未保留，因此对照以专题报告中已固化的正式结果为准。

## 2. standard 对比

### 2.1 吞吐与延迟

| 指标 | mysql-only | db-cache | 结论 |
| --- | ---: | ---: | --- |
| `baseline-100 QPS` | 219.02 | 199.33 | 都在 `~200 req/s` 量级 |
| `baseline-200 QPS` | 195.08 | 212.65 | 差异不大，仍受单行库存更新限制 |
| `baseline-500 QPS` | 214.58 | 207.56 | 都未随并发继续线性提升 |
| `baseline-500 Avg RT(ms)` | 2265.56 | 2344.12 | 都出现明显排队 |
| `baseline-500 P99(ms)` | 3355 | 3295 | 尾延迟量级接近 |

### 2.2 锁竞争

| 指标 | mysql-only | db-cache |
| --- | ---: | ---: |
| `baseline-500 RowLockWaitsDelta` | 13614 | 12265 |
| `baseline-500 RowLockTimeDeltaMs` | 475740 | 507923 |
| 最重 SQL | `UPDATE tb_seckill_voucher ...` | `UPDATE tb_seckill_voucher ...` |

结论：

- `db-cache` 没有改变 standard 写路径的核心瓶颈
- 成功下单最终都要竞争同一条库存更新 SQL
- 因此 standard 场景是“数据库写热点问题”，不是“有没有 Redis 的问题”

## 3. flash-sale 对比

### 3.1 入口吞吐与响应时间

| 指标 | mysql-only | db-cache | 提升 |
| --- | ---: | ---: | ---: |
| `flash-burst-5k-100 QPS` | 438.54 | 782.44 | `1.78x` |
| `flash-burst-5k-500 QPS` | 410.70 | 411.23 | `1.00x` |
| `flash-sustain-5k-100 QPS` | 577.44 | 1190.36 | `2.06x` |
| `flash-sustain-5k-500 QPS` | 574.55 | 1184.95 | `2.06x` |
| `flash-sustain-5k-100 Avg RT(ms)` | 804.97 | 374.20 | `-53.5%` |
| `flash-sustain-5k-500 Avg RT(ms)` | 813.23 | 363.64 | `-55.3%` |

说明：

- `flash-burst-5k-500` 中两者 QPS 接近，说明短时爆发更容易受单机接入上限干扰
- 真正体现架构差异的是 sustain 场景，`db-cache` 在持续高反差压力下明显更稳

### 3.2 一致性与失败分布

| 指标 | mysql-only | db-cache |
| --- | ---: | ---: |
| `flash-sustain-5k-100 FinalOrders` | 100 | 100 |
| `flash-sustain-5k-500 FinalOrders` | 500 | 500 |
| 是否超卖 | 否 | 否 |
| sustain 失败主因 | `Out of stock` + 少量连接失败 | 全部为 `库存不足` |

结论：

- 两种架构的一致性都正确
- `db-cache` 的优势不是“卖出更多订单”，而是“更快挡住卖不出去的请求”

### 3.3 数据库锁等待

| 指标 | mysql-only | db-cache | 降幅 |
| --- | ---: | ---: | ---: |
| `flash-sustain-5k-100 RowLockWaitsDelta` | 30894 | 99 | `-99.68%` |
| `flash-sustain-5k-500 RowLockWaitsDelta` | 30748 | 321 | `-98.96%` |
| `flash-sustain-5k-100 RowLockTimeDeltaMs` | 69858 | 4507 | `-93.55%` |
| `flash-sustain-5k-500 RowLockTimeDeltaMs` | 75369 | 8606 | `-88.58%` |

结论：

- 这是当前两种架构最有价值的差异
- `db-cache` 把无效失败流量大量截断在数据库之前
- MySQL 从“持续承受高并发失败请求冲击”变成“主要处理真正可能成功的请求”

## 4. 读缓存收益

这一部分只属于 `db-cache`：

| 场景 | QPS | Avg RT(ms) |
| --- | ---: | ---: |
| `cache-hit-200` | 6583.55 | 28.42 |
| `cache-penetration-200` | 8919.33 | 20.01 |
| `cache-breakdown-200` | 8401.67 | 22.11 |

结论：

- `mysql-only` 没有这类收益面
- `db-cache` 不仅改善 flash-sale 入口，也显著改善读路径

## 5. 总结

- 如果目标是提升常规成功写路径吞吐，`mysql-only` 和 `db-cache` 都会被 MySQL 热点库存行卡住。
- 如果目标是承受更真实的 flash-sale 高反差流量，`db-cache` 明显优于 `mysql-only`，核心收益是入口过滤和数据库减压。
- 如果下一阶段要继续提升“成功订单吞吐”而不仅是“入口承载能力”，后续仍需要 `db-cache-mq` 这类异步化方案。
