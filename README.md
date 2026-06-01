# LivPick

## 项目简介

`LivPick` 是一个围绕本地生活业务场景构建的后端项目，重点不是普通 CRUD，而是把高并发秒杀场景下常见的核心问题真正落到业务链路中，包括：

- 秒杀防超卖
- 一人一单
- 异步削峰
- 超时关单
- 库存回补
- 缓存穿透 / 击穿 / 一致性
- 失败补偿与最终一致性

当前仓库对应的是三阶段演进中的最终版本：`db-cache-mq`。  
它是在 `mysql-only` 和 `db-cache` 两个阶段基础上，继续引入 `Kafka`、`Redisson` 和 benchmark 观测能力后形成的完整业务闭环版本。

---

## 架构演进

### 1. mysql-only

最早版本所有请求直接进入数据库执行库存扣减和订单创建。

优点：

- 实现简单
- 逻辑直观

缺点：

- 高并发下大量失败请求也会持续冲击 MySQL
- 热点库存行竞争严重
- 秒杀入口吞吐低

### 2. db-cache

第二阶段引入 `Redis + Lua`，把库存校验和一人一单判断前置到缓存层。

优点：

- 大量失败请求在 Redis 层快速返回
- 显著降低数据库热点写竞争
- 在本机单节点压测下，入口吞吐表现最强

局限：

- 真正成功的请求仍然是同步写库
- 超时关单、补偿恢复、异步削峰能力不完整

### 3. db-cache-mq

当前版本在 `db-cache` 基础上继续引入：

- `Kafka` 异步削峰和异步落库
- `Redisson` 延迟队列处理超时关单
- pending-send 补偿重试
- 取消后库存与购买资格恢复
- 复用已取消订单再次抢购
- benchmark 指标、链路 drain、consumer pause 等观测能力

它的核心价值不是“本机单节点 QPS 一定最高”，而是：

- 把成功请求从同步写库改造成异步闭环
- 让系统具备补偿、恢复和最终一致性能力
- 把秒杀业务状态机真正补完整

---

## 核心业务能力

### 秒杀主链路

当前秒杀链路为：

1. 用户请求秒杀接口
2. 生成 `orderId`
3. 执行 `Redis + Lua` 原子校验
4. Lua 完成：
   - 判断库存是否充足
   - 判断是否重复下单
   - 预扣 Redis 库存
   - 记录用户购买资格
5. 注册 Redis pending-send 记录
6. 发送 `SeckillOrderMessage` 到 Kafka
7. Kafka Consumer 异步执行：
   - 获取分布式锁
   - 检查历史订单
   - 扣减 MySQL 库存
   - 创建订单或重激活已取消订单
   - 投递超时关单消息

### 超时关单与回补

订单创建后会投递超时消息：

- `Redisson RDelayedQueue` 作为主链路，按订单到期时间触发
- `Spring Task` 低频扫描作为兜底，处理漏单或异常场景

关单成功后会执行：

- 更新订单状态为 `CANCELLED`
- 回补数据库库存
- 回补 Redis 库存
- 恢复用户购买资格
- 写入可复用订单标记，支持再次抢购时复用原订单

### 缓存治理

当前项目同时覆盖三类典型缓存问题：

- 缓存穿透：`RBloomFilter + 缓存空值`
- 缓存击穿：逻辑过期
- 缓存一致性：旁路缓存 + 删除失败 Kafka 补偿重试 + TTL 兜底

---

## 技术栈

- `Spring Boot 2.3.12.RELEASE`
- `MyBatis-Plus`
- `MySQL`
- `Redis`
- `Lua`
- `Kafka`
- `Redisson`
- `Docker Compose`
- `JMeter`

---

## 目录结构

主代码位于：

```text
src/main/java/com/livepick
```

核心目录：

- `controller`：接口入口
- `service / service/impl`：业务实现
- `mapper`：数据库访问
- `mq`：Kafka 生产者、消费者、消息体、延迟队列
- `task`：定时补偿任务
- `config`：业务配置、Kafka Topic、Redisson 等
- `utils`：缓存工具、ID 生成、拦截器、常量等

与压测和总结相关的目录：

- `benchmark-final/`
- `benchmark-final/reports/`
- `benchmark-final/suites/db-cache-mq/`
- `interview/`

---

## 压测结论摘要

### 压测口径

本轮 `db-cache-mq` 正式结果基于以下环境：

- JDK 11
- MySQL 独立 schema：`livpick_db_cache_mq`
- Redis 单节点：`livpick-redis`
- Kafka 单 broker、`1 partition`
- benchmark 用户旁路：`X-Benchmark-User-Id`

说明：

- `mysql-only` 和 `db-cache` 继续引用历史同机报告
- `db-cache-mq` 为本轮补跑
- 主结论优先看高反差秒杀 `flash-sale` 场景，不拿大库存 `baseline` 做核心卖点

### flash-sale 正式结果

#### sustain 场景

| 场景 | Samples | 入口 QPS | 平均响应(ms) | 成功受理数 | 最终建单数 | 最终库存 | Lua 前置拒绝数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `flash-sustain-5k-100` | 34916 | 584.75 | 805.22 | 100 | 100 | 0 | 34816 |
| `flash-sustain-5k-500` | 34490 | 577.95 | 807.88 | 500 | 500 | 0 | 33990 |

#### burst 场景

| 场景 | Samples | 入口 QPS | 平均响应(ms) | 成功受理数 | 最终建单数 | 最终库存 | Lua 前置拒绝数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `flash-burst-5k-100` | 5442 | 555.48 | 1142.99 | 100 | 100 | 0 | 5112 |
| `flash-burst-5k-500` | 5073 | 515.50 | 1278.85 | 500 | 500 | 0 | 4400 |

可以得到两个重要结论：

- 在 `5k` 级并发、库存仅 `100/500` 的高反差场景下，真正进入成功链路的请求被稳定压缩到库存量级
- 最终 `FinalStock=0` 且 `FinalOrders=库存量`，说明异步落库没有破坏正确性

### 三种架构的总体排序

如果只看当前本机单节点入口吞吐：

`db-cache > db-cache-mq > mysql-only`

如果看综合工程能力和架构终态：

`db-cache-mq > db-cache > mysql-only`

原因是：

- `db-cache` 在当前本机环境下入口过滤更充分，吞吐更强
- `db-cache-mq` 虽然没有在当前单 broker 单 partition 下全面跑赢 `db-cache`
- 但它补齐了异步削峰、超时关单、库存回补、补偿重试和最终一致性闭环，是更完整的工程终态

详细对比见：

- [`benchmark-final/reports/db-cache-mq.md`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\benchmark-final\reports\db-cache-mq.md)
- [`interview/architecture-comparison.md`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\interview\architecture-comparison.md)

---

## 瓶颈分析

### 当前 db-cache-mq 的主要瓶颈

当前 `db-cache-mq` 没有在本机单节点环境下全面超过 `db-cache`，主要不是 Redis 入口问题，而是成功请求后半段链路存在瓶颈：

#### 1. Kafka 只有单分区，消费天然单通道

当前配置：

- 单 broker
- `1 partition`
- 单 consumer group

这意味着 Kafka 更像“单通道异步缓冲层”，而不是“多分区并行消费引擎”。

#### 2. 生产端发送还是同步等待

当前 `sendSeckillOrder(...)` 使用了：

- `kafkaTemplate.send(...).get(5, TimeUnit.SECONDS)`

虽然业务形态引入了 MQ，但接口线程仍然会等待 broker ack，这会抬高接口 RT，也限制入口线程吞吐。

#### 3. 消费端单条消息事务过重

每条成功消息都需要执行：

- 分布式锁
- 查历史订单
- 扣减 DB 库存
- 插入或重激活订单
- 投递超时关单消息

所以 Kafka 只是把同步写库压力后移了，没有减少成功请求最终必须完成的数据库工作量。

#### 4. MySQL 仍然是最终落库瓶颈

成功订单最终还是要回到 MySQL 执行库存扣减和订单写入，因此它仍然受数据库写路径约束。

#### 5. 当前 debug 日志会放大消费成本

目前 `logging.level.com.livepick=debug`，消费者高频打印 debug 日志，会增加 I/O 和字符串序列化成本。

---

## 后续优化方向

如果继续迭代 `db-cache-mq`，优先级建议如下：

### 1. 先把 Kafka 发送改成真正异步

优先去掉主链路里的同步 `.get(...)`，改成回调模式：

- 发送成功后清除 pending-send
- 发送失败后保留 pending-send，交给补偿任务重试

这是最直接降低接口 RT 的优化点。

### 2. 提升 Kafka 并行度

建议补做敏感性实验：

- `1 partition`
- `2 partitions`
- `4 partitions`

这样才能验证当前瓶颈究竟是架构本身，还是单分区配置限制。

### 3. 提升消费者并发

如果增加了 partition，消费端也要同步增加并发配置，否则 Kafka 并行能力无法真正释放。

### 4. 精简消费端单条事务路径

重点看：

- 是否能减少不必要的 DB 查询
- 是否能减少额外 Redis 回写
- 是否能优化超时消息投递成本

### 5. 压测时降低日志级别

正式 benchmark 时建议至少将：

- `com.livepick` 日志级别降到 `info`

避免高频 debug 日志污染吞吐结果。

---

## 为什么最终仍然选择 db-cache-mq

这个项目最后选择 `db-cache-mq`，不是因为它在当前本机单节点压测下所有数字都最好，而是因为它最符合秒杀系统的完整目标：

- 不只是挡住失败请求
- 还要把成功请求做成可异步消化的链路
- 出现异常后还能补偿、恢复、回补
- 最终保证库存、订单、资格状态一致

所以：

- `db-cache` 更像“入口过滤最强”的架构
- `db-cache-mq` 更像“业务闭环最完整”的架构

如果是写项目总结、简历或面试表达，推荐的结论是：

- `db-cache` 体现了 Redis 前置过滤对热点数据库减压的收益
- `db-cache-mq` 体现了秒杀系统从同步写库演进到异步削峰和最终一致性闭环的完整工程能力

---

## 本地启动

### 环境要求

- JDK 11
- MySQL
- Docker Desktop

### 中间件

当前推荐本地部署方式：

- MySQL：本机
- Redis：Docker 单实例
- Kafka：Docker 单 broker

相关目录：

```text
docker/compose
```

启动方式：

```powershell
cd docker\compose
docker compose --env-file .env -f docker-compose.middleware.yml up -d
```

### 应用运行

当前项目配置支持通过环境变量注入：

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `KAFKA_BOOTSTRAP_SERVERS`
- `LIVPICK_BENCHMARK_ENABLED`
- `LIVPICK_BENCHMARK_SKIP_LOGIN_CHECK`

本轮 benchmark 相关运行时，推荐：

```text
DB_NAME=livpick_db_cache_mq
LIVPICK_BENCHMARK_ENABLED=true
LIVPICK_BENCHMARK_SKIP_LOGIN_CHECK=true
```

---

## 相关文档

- [`benchmark-final/reports/db-cache-mq.md`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\benchmark-final\reports\db-cache-mq.md)
- [`interview/architecture-comparison.md`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\interview\architecture-comparison.md)
- [`interview/resume-and-interview-playbook.md`](C:\Users\heyunhui\IdeaProjects\LivPick-db-cache-mq\interview\resume-and-interview-playbook.md)
