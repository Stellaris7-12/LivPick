# LivPick

## 项目简介

`LivPick` 是一个面向本地生活场景的后端项目，围绕店铺信息查询、优惠券活动和高并发秒杀下单展开。  
项目基于 Spring Boot 单体架构实现，重点不在普通 CRUD，而在于把 `Redis + Lua + Kafka + Redisson` 真正落到业务链路里，解决高并发秒杀、缓存治理、异步削峰、延迟任务和最终一致性问题。

这个仓库是在原始点评类练手项目基础上持续演进得到的，所以 `pom.xml` 中仍然保留了部分历史命名；当前主代码包路径已经统一为：

```text
src/main/java/com/livepick
```

## 核心能力概览

当前项目重点能力包括：

- 店铺详情查询、分页查询与 GEO 附近店铺搜索
- 优惠券与秒杀券管理
- Redis + Lua 秒杀资格校验与库存预扣
- Kafka 异步落库
- Redisson 延迟队列 + SpringTask 实现超时关单与库存回流
- Redisson Bloom Filter + 缓存空值解决缓存穿透
- 更新数据库后删缓存，失败走 Kafka 补偿重试

项目中仍保留的基础业务能力包括：

- 手机验证码登录与 Redis 登录态
- 探店笔记、点赞、关注、Feed 流
- 用户签到与连续签到统计

## 技术栈

- `Spring Boot 2.3.12.RELEASE`：应用启动、Web API、依赖整合
- `MyBatis-Plus`：数据库访问与链式 CRUD
- `MySQL`：核心业务数据持久化
- `Redis`：缓存、秒杀库存、一人一单集合、GEO、分布式 ID、自定义锁
- `Lua`：秒杀资格校验与 Redis 预扣原子化
- `Kafka`：秒杀异步落库、缓存删除失败补偿重试
- `Redisson`：分布式锁、延迟队列、布隆过滤器
- `Docker Compose`：本地联调 Redis + Kafka

## 项目结构

主代码位于 `src/main/java/com/livepick`：

- `controller`：REST 接口入口
- `service / service/impl`：核心业务实现
- `mapper`：MyBatis-Plus 数据访问
- `mq`：Kafka Producer / Consumer、消息体、延迟队列组件
- `task`：Spring 定时兜底任务
- `config`：Kafka、Redisson、业务配置类
- `utils`：缓存组件、分布式 ID、用户上下文、常量等

资源与部署相关目录：

- `src/main/resources/application.yaml`：项目主配置
- `src/main/resources/db/hmdp.sql` / `hmdp2.sql`：数据库初始化与补充索引脚本
- `src/main/resources/seckill.lua`：秒杀原子校验脚本
- `src/main/resources/seckill_rollback.lua`：秒杀发送失败回滚脚本
- `docker/compose`：本地 Docker Compose 中间件配置
- `interview`：面试讲解与原理梳理文档

## 当前核心架构

当前重点链路可以概括为：

`Redis + Lua` 前置做资格校验和库存预扣，`Kafka` 承接异步削峰与补偿消息，`Redisson` 提供锁、延迟队列和布隆过滤器能力，`MySQL` 做最终落库与状态兜底。

其中：

- Redis 不只是缓存，还承担秒杀库存、已下单用户集合、GEO、分布式 ID 等职责
- Kafka 当前主要承接两类消息：
  - `seckill-order-create`
  - `cache-shop-delete-retry`
- Redisson 当前主要承接三类能力：
  - `RLock`
  - `RDelayedQueue`
  - `RBloomFilter`

> 当前实现已经从旧版 Redis Stream 异步秒杀方案迁移到 Kafka 方案。

## 重点业务链路

### 1. 秒杀下单链路

要解决的问题：

- 高并发请求直接打数据库会导致热点行竞争严重
- 需要同时保证库存不超卖和一人一单
- 同步写库会拖慢接口响应并压垮数据库

当前主流程：

1. 用户请求秒杀接口
2. 服务端生成 `orderId`
3. 执行 `seckill.lua`
4. Lua 在 Redis 中原子完成：
   - 判断库存是否充足
   - 判断是否重复下单
   - 扣减 Redis 库存
   - 记录用户下单资格
5. Lua 成功后构造 `SeckillOrderMessage` 并发送 Kafka
6. Kafka Consumer 异步消费消息，执行：
   - Redisson 按 `userId` 加锁
   - 查询数据库是否已有订单
   - 扣减数据库库存
   - 创建订单
7. 下单成功后投递延迟关单消息

为什么这样设计：

- Redis + Lua 先把大部分无效请求挡在数据库外
- Kafka 把瞬时高并发流量削峰成平滑消费流量
- MySQL 只承担最终落库，不再承担所有前置资格判断

幂等与兜底：

- Redis Lua 入口做第一层一人一单保护
- Consumer 端按 `userId` 加 Redisson 分布式锁
- `tb_voucher_order(voucher_id, user_id)` 唯一索引做数据库最终兜底

### 2. 超时关单链路

要解决的问题：

- 下单成功但一直不支付，会长期占用库存
- 需要在超时后自动取消订单并恢复库存

当前主流程：

1. 订单创建成功后构造 `OrderTimeoutMessage`
2. 写入 Redisson `RDelayedQueue`
3. 到期后消息被搬运到阻塞队列
4. 后台消费线程取出消息，调用 `closeTimeoutOrder(orderId)`
5. 仅当订单仍为 `UNPAID` 时，才更新为 `CANCELLED`
6. 回补 MySQL 秒杀库存
7. 回补 Redis 库存并移除用户资格集合

兜底机制：

- `RDelayedQueue` 负责主链路的准实时关单
- `SpringTask` 每 60 秒扫描一次超时未支付订单，负责扫漏

当前默认配置：

- 超时未支付时间：`15` 分钟
- 兜底扫描间隔：`60000ms`

### 3. 缓存一致性链路

要解决的问题：

- 更新数据库后如果删缓存失败，会出现脏数据

当前主流程：

1. 先更新数据库
2. 再删除缓存
3. 如果删缓存失败，发送 `CacheDeleteRetryMessage`
4. Kafka Consumer 继续重试删缓存
5. 超过最大重试次数后记录错误日志

为什么当前这样设计：

- 比延迟双删更直接
- 比 canal 成本更低
- 与当前项目已有 Kafka 技术栈更契合

## Redis / Redisson / Kafka 在项目中的角色

### Redis 数据结构

- `String`
  - `seckill:stock:{voucherId}`：秒杀库存
  - `cache:shop:{shopId}`：店铺详情缓存
  - `icr:order:{date}`：分布式 ID 自增计数
- `Set`
  - `seckill:order:{voucherId}`：已下单用户集合
- `GEO`
  - `shop:geo:{typeId}`：店铺坐标索引

### Redisson 组件

- `RLock`
  - Kafka 消费秒杀消息时按 `userId` 加锁，减少重复消费冲突
- `RDelayedQueue`
  - 订单超时自动关闭
- `RBloomFilter`
  - 店铺 ID 预过滤，拦截不存在的查询请求

### Kafka Topic

- `seckill-order-create`
  - 秒杀异步落库消息
- `cache-shop-delete-retry`
  - 删缓存失败补偿重试消息

### 秒杀消息体字段

当前 `SeckillOrderMessage` 包含：

- `orderId`
- `userId`
- `voucherId`
- `createTime`

其中 `orderId` 由 [RedisIdWorker](src/main/java/com/livepick/utils/RedisIdWorker.java) 生成，采用“时间戳 + Redis 自增序列”的方式保证全局唯一、趋势递增。

## 本地部署与启动

这一节作为本项目唯一的部署入口，目标是：**在本机通过 Docker 启动 Redis 和 Kafka，配合本地 MySQL，把当前业务闭环跑通**。

### 1. 部署方案

当前推荐方案：

- MySQL：本地自行启动
- Redis：Docker 单实例
- Kafka：Docker 单 broker、KRaft 模式

这样设计的原因是：

- 当前目标是先验证业务闭环，不是验证中间件高可用
- Kafka 当前 topic 配置就是单分区、单副本
- 单 broker / 单实例更容易部署、排障和观察日志

适合：

- 本地开发
- 功能联调
- 小规模验证异步链路

不适合：

- 生产环境
- 高可用容灾测试
- 更真实的多 broker 压测

### 2. 依赖环境

开始前请先确认：

1. 已安装 Docker Desktop
2. Docker Desktop 当前使用 Linux containers
3. 本机端口未被占用：
   - `6379`：Redis
   - `9092`：Kafka client
   - `9093`：Kafka controller
4. MySQL 已准备好，且项目可访问

### 3. Docker Compose 目录

相关文件位于：

```text
docker/
  compose/
    docker-compose.middleware.yml
    .env.example
    README.md
```

### 4. 中间件配置文件

当前 Compose 会启动两个容器：

1. `livpick-redis`
2. `livpick-kafka`

默认端口：

- Redis：`localhost:6379`
- Kafka：`localhost:9092`

这与当前 `application.yaml` 默认配置一致，通常不需要额外改代码配置。

### 5. 启动步骤

先进入目录：

```powershell
cd docker\compose
```

如果本目录下还没有 `.env` 文件，先基于 `.env.example` 创建一份 `.env`。  
默认值通常可以直接使用。

启动 Redis + Kafka：

```powershell
# --env-file .env：指定环境变量文件
# -f docker-compose.middleware.yml：指定 compose 配置文件
# up：创建并启动服务
# -d：后台运行
docker compose --env-file .env -f docker-compose.middleware.yml up -d
```

查看当前容器状态：

```powershell
# ps：查看当前 compose 管理的容器状态
docker compose --env-file .env -f docker-compose.middleware.yml ps
# docker ps：查看当前正在运行的所有容器
docker ps
```

停止但保留容器：后面还会继续联调，希望下次直接再启动

```powershell
docker compose --env-file .env -f docker-compose.middleware.yml stop
```



执行了 `stop` 命令（停止容器但保留容器和卷），想再次运行这些容器


```powershell
# 使用 `start` 命令：直接启动已存在的容器，不检查配置变化：（推荐）
docker compose --env-file .env -f docker-compose.middleware.yml start
# 使用 `up` 命令：如果容器已存在且配置未变，它会直接启动现有容器；如果检测到配置或镜像有变化，可能会重新创建容器（但卷数据仍保留）
docker compose --env-file .env -f docker-compose.middleware.yml up -d
```


停止并移除容器：这轮联调结束了，想把容器收掉，但还想保留 Redis / Kafka 的数据，下次再起时继续用

```powershell
# down：停止并移除当前 compose 创建的容器和网络
docker compose --env-file .env -f docker-compose.middleware.yml down
```

关闭并删除卷：彻底重置本地中间件状态

```powershell
# -v：连同命名卷一起删除，会清空 Redis / Kafka 持久化数据
docker compose --env-file .env -f docker-compose.middleware.yml down -v
```

### 6. IDEA 环境变量配置

如果你的 Spring Boot 项目是**直接在 IDEA 里启动**，而 MySQL / Redis / Kafka 是跑在本机或 Docker 映射到宿主机端口，那么推荐通过 IDEA 的运行配置注入环境变量，而不是把真实密码写进仓库里的 `application.yaml`。

当前 `application.yaml` 使用的是占位符写法，例如：

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:127.0.0.1}:${DB_PORT:3306}/hmdp?useSSL=false&serverTimezone=UTC
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
  redis:
    host: ${REDIS_HOST:127.0.0.1}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}
```

含义是：

- Spring 启动时优先读取环境变量
- 如果没有对应环境变量，就使用冒号后的默认值

#### 配置路径

1. 打开 IDEA
2. 右上角找到启动项，例如 `LivPickApplication`
3. 点击下拉框
4. 选择 `Edit Configurations...`
5. 在对应的 Spring Boot 运行配置里找到 `Environment variables`

#### 推荐环境变量

如果你当前是：

- MySQL 本机启动
- Redis / Kafka 通过 Docker 映射到宿主机端口
- Spring Boot 直接在 IDEA 中运行

那么推荐填写下面这组：

```text
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USERNAME=root
DB_PASSWORD=你的MySQL密码
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
```

如果 IDEA 需要一行格式，也可以写成：

```text
DB_HOST=127.0.0.1;DB_PORT=3306;DB_USERNAME=root;DB_PASSWORD=你的MySQL密码;REDIS_HOST=127.0.0.1;REDIS_PORT=6379;REDIS_PASSWORD=;KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
```

#### 配置说明

- `DB_HOST` / `DB_PORT`：MySQL 地址和端口
- `DB_USERNAME` / `DB_PASSWORD`：MySQL 账号密码
- `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`：Redis 连接信息
- `KAFKA_BOOTSTRAP_SERVERS`：Kafka broker 地址

#### 一个关键区别

- 如果应用跑在 IDEA 里：Redis / Kafka 地址写 `127.0.0.1` 或 `localhost`
- 如果应用以后也跑进 Docker 容器里：地址就不能写 `127.0.0.1`，而应该写 compose 服务名，例如 `redis`、`kafka:9092`

你当前这个项目阶段，应用是直接在 IDEA 里启动，所以推荐使用：

- `REDIS_HOST=127.0.0.1`
- `KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092`

### 7. MySQL 初始化

请先准备 `hmdp` 数据库，并导入：

```text
src/main/resources/db/hmdp.sql
```

如需补充新版索引脚本，可同步参考：

```text
src/main/resources/db/hmdp2.sql
```

### 8. 启动后验证

#### 验证 Redis

```powershell
# exec：在容器内执行命令
# -it：进入交互式终端
docker exec -it livpick-redis redis-cli
```

进入后执行：

```text
PING
```

返回 `PONG` 说明正常。

#### 验证 Kafka

查看日志：

```powershell
# logs：查看容器日志
docker logs livpick-kafka
# -f：持续跟踪日志输出
docker logs -f livpick-kafka
```

查看 topic：

```powershell
# /opt/kafka/bin/kafka-topics.sh：Kafka 自带的 topic 管理工具
# --bootstrap-server：指定要连接的 Kafka broker 地址
# --list：列出所有 topic
docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### 9. Topic 说明

当前项目会用到的 topic：

- `seckill-order-create`
- `cache-shop-delete-retry`

应用启动后，`KafkaTopicConfig` 通常会自动创建。  
如果你想手动创建：

```powershell
# --create：创建 topic
# --topic：topic 名称
# --partitions 1：分区数为 1
# --replication-factor 1：副本数为 1，适合当前单 broker 联调环境
docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic seckill-order-create --partitions 1 --replication-factor 1

docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic cache-shop-delete-retry --partitions 1 --replication-factor 1
```

### 10. 常用 Docker / 中间件命令

#### 查看与管理容器

```powershell
# ps：查看当前 compose 项目的容器状态
docker compose --env-file .env -f docker-compose.middleware.yml ps
# docker ps：查看所有运行中的容器
docker ps
# docker ps -a：查看所有容器，包括已停止的
docker ps -a
# stop：停止容器，但保留容器和卷数据
docker compose --env-file .env -f docker-compose.middleware.yml stop
# restart：重启 compose 项目中的容器
docker compose --env-file .env -f docker-compose.middleware.yml restart
# up -d redis：只启动 redis 服务并后台运行
docker compose --env-file .env -f docker-compose.middleware.yml up -d redis
# up -d kafka：只启动 kafka 服务并后台运行
docker compose --env-file .env -f docker-compose.middleware.yml up -d kafka
# stop redis：只停止 redis 服务
docker compose --env-file .env -f docker-compose.middleware.yml stop redis
# stop kafka：只停止 kafka 服务
docker compose --env-file .env -f docker-compose.middleware.yml stop kafka
```

#### 查看日志

```powershell
# 查看 Redis 一次性日志输出
docker logs livpick-redis
# -f：持续跟踪 Redis 日志
docker logs -f livpick-redis
# 查看 Kafka 一次性日志输出
docker logs livpick-kafka
# -f：持续跟踪 Kafka 日志
docker logs -f livpick-kafka
# compose logs：查看当前 compose 项目的聚合日志
docker compose --env-file .env -f docker-compose.middleware.yml logs
# 只持续跟踪 kafka 服务日志
docker compose --env-file .env -f docker-compose.middleware.yml logs -f kafka
```

#### 进入容器

```powershell
# sh：进入 Redis 容器的 shell
docker exec -it livpick-redis sh
# 直接进入 Redis 客户端
docker exec -it livpick-redis redis-cli
# bash：进入 Kafka 容器 shell
docker exec -it livpick-kafka bash
```

#### 常用 Redis 命令

```powershell
# PING：验证 Redis 是否可用
docker exec -it livpick-redis redis-cli PING
# GET：查看当前秒杀库存
docker exec -it livpick-redis redis-cli GET seckill:stock:1
# SMEMBERS：查看某张券的已下单用户集合
docker exec -it livpick-redis redis-cli SMEMBERS seckill:order:1
# SET：手动预置秒杀库存
docker exec -it livpick-redis redis-cli SET seckill:stock:1 100
# DEL：清空该券的历史下单用户集合
docker exec -it livpick-redis redis-cli DEL seckill:order:1
```

#### 常用 Kafka 命令

```powershell
# --list：列出所有 topic
docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# --describe：查看某个 topic 的分区、副本等详情
docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic seckill-order-create
# --create：手动创建 topic
docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic seckill-order-create --partitions 1 --replication-factor 1
```

## 业务闭环验证

建议按下面顺序验证，不要一上来就压测。

### 1. 预置秒杀库存

当前代码里没有看到自动把秒杀券库存预热到 Redis 的逻辑，所以验证秒杀前需要先手动预置。

例如验证 `voucherId=1`：

```powershell
docker exec -it livpick-redis redis-cli SET seckill:stock:1 100
docker exec -it livpick-redis redis-cli DEL seckill:order:1
```

这样可以确保：

- `seckill:stock:1` 有初始库存
- `seckill:order:1` 不残留历史下单用户集合

### 2. 验证店铺查询缓存链路

- 启动 MySQL、Redis、Kafka、项目
- 调用店铺查询接口
- 观察 Redis 是否写入 `cache:shop:{id}`
- 验证布隆过滤器和缓存空值是否生效

### 3. 验证秒杀下单链路

- 调用秒杀接口
- 观察 Redis 库存是否减少
- 观察 `seckill:order:{voucherId}` 是否写入 `userId`
- 观察 Kafka 是否收到下单消息
- 验证 MySQL 是否成功创建订单

### 4. 验证超时关单链路

- 下单成功后不支付
- 等待超过 `15` 分钟，或临时将配置缩短后联调
- 验证：
  - 订单状态是否更新为取消
  - MySQL 库存是否回补
  - Redis 库存和用户资格集合是否回补

### 5. 验证缓存删除补偿链路

- 调用店铺更新接口
- 模拟删缓存失败
- 观察 Kafka 是否收到补偿消息
- 验证补偿 Consumer 是否继续执行删除重试

## 当前进度与后续迭代

### 1. 本轮优化目标

本轮优化的目标是先把简历中新增的核心能力补进当前项目分支，形成可编译、可继续扩展的代码骨架，重点覆盖以下 4 个方向：

1. Kafka 替换 Redis Stream，实现秒杀异步落库
2. Kafka 实现缓存删除补偿重试
3. Redisson 延迟队列实现超时关单与库存回流骨架
4. Redisson Bloom Filter + 缓存空值实现缓存穿透防护骨架

### 2. 当前已完成内容

#### 2.1 Kafka 异步落库

已完成：

- `seckill.lua` 已由“校验 + Redis Stream 入队”改为“只做库存校验、一人一单校验和 Redis 预扣减”
- `VoucherOrderServiceImpl.seckillVoucher()` 已改为 Lua 成功后发送 Kafka 下单消息
- 已新增 `SeckillOrderMessage`、Kafka Producer、Kafka Consumer
- Kafka Consumer 已实现异步消费、查重、数据库扣库存、订单创建
- 消费端已保留 Redisson 分布式锁，避免重复消费导致重复下单
- Kafka 发送失败时，已通过 `seckill_rollback.lua` 回滚 Redis 资格占用
- 已补充支付入口和基于订单状态条件更新的支付成功逻辑
- 已补充消费异常日志和重复消费分支处理

核心文件：

- [VoucherOrderServiceImpl](src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java)
- [LivPickKafkaProducer](src/main/java/com/livepick/mq/producer/LivPickKafkaProducer.java)
- [SeckillOrderConsumer](src/main/java/com/livepick/mq/consumer/SeckillOrderConsumer.java)
- [VoucherOrderController](src/main/java/com/livepick/controller/VoucherOrderController.java)
- [seckill.lua](src/main/resources/seckill.lua)
- [seckill_rollback.lua](src/main/resources/seckill_rollback.lua)

#### 2.2 缓存删除补偿重试

已完成：

- 店铺更新流程已改为“更新数据库后删除缓存”
- 删除缓存失败时，已发送 Kafka 补偿消息
- 已新增 `CacheDeleteRetryMessage`
- 已新增 Kafka Consumer 执行删缓存重试
- 已支持最大重试次数控制和失败日志输出

核心文件：

- [ShopServiceImpl](src/main/java/com/livepick/service/impl/ShopServiceImpl.java)
- [CacheDeleteRetryConsumer](src/main/java/com/livepick/mq/consumer/CacheDeleteRetryConsumer.java)
- [CacheClient](src/main/java/com/livepick/utils/CacheClient.java)

#### 2.3 Redisson 延迟队列超时关单

已完成：

- 订单创建成功后已投递 Redisson 延迟队列消息
- 已新增 `OrderTimeoutMessage`
- 延迟队列消费者已实现到期关单
- 关单逻辑已使用订单状态条件更新，避免支付与关单并发冲突
- 关单成功后已回补数据库库存和 Redis 秒杀资格
- 已新增定时扫描任务作为兜底
- 已补充支付成功与超时关单的最小闭环，支持演示状态竞争场景

核心文件：

- [OrderTimeoutDelayQueueManager](src/main/java/com/livepick/mq/delay/OrderTimeoutDelayQueueManager.java)
- [OrderTimeoutFallbackTask](src/main/java/com/livepick/task/OrderTimeoutFallbackTask.java)
- [VoucherOrderServiceImpl](src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java)

#### 2.4 Redisson Bloom Filter + 缓存空值

已完成：

- 已新增 `ShopBloomFilterService`，基于 Redisson `RBloomFilter` 初始化店铺布隆过滤器
- 店铺查询链路已接入“布隆过滤器预判 + 缓存空值兜底”
- 缓存工具类已新增布隆过滤器版查询方法
- 启动时若过滤器为空，会尝试从数据库装载店铺 ID

核心文件：

- [ShopBloomFilterService](src/main/java/com/livepick/service/ShopBloomFilterService.java)
- [CacheClient](src/main/java/com/livepick/utils/CacheClient.java)
- [ShopServiceImpl](src/main/java/com/livepick/service/impl/ShopServiceImpl.java)

#### 2.5 基础配置与可编译状态

已完成：

- 新增 Kafka 依赖和 Topic 配置
- 新增 `LivPickProperties` 统一管理 Kafka、延迟队列、缓存重试、布隆过滤器配置
- `RedissonConfig` 已改为从 `application.yaml` 读取 Redis 配置
- Lombok 已升级到兼容当前 JDK 的版本
- `tb_voucher_order` 已补充 `(voucher_id, user_id)` 唯一索引脚本，作为一人一单数据库兜底
- 当前分支已执行 `mvn compile` 并通过
- 已新增 Kafka Consumer 与缓存补偿 Consumer 的定向单元测试并通过

### 3. 后续代码待完善内容

#### 3.1 Kafka 秒杀链路

- 补充更完整的消息发送补偿机制，例如本地消息表、Outbox 或 Redis 待发送标记
- 增加 Kafka 消费失败重试、死信队列和消息追踪能力
- 完善消费者幂等处理，避免极端情况下重复消费带来的边界问题
- 评估是否需要把“重复键冲突”与“真实系统异常”进一步拆分成更细的监控指标

#### 3.2 缓存删除补偿链路

- 增加延迟重试、指数退避和死信队列
- 将当前店铺缓存补偿抽象为通用补偿组件，覆盖更多业务缓存
- 增加告警机制，而不只是输出错误日志
- 进一步细化消息体字段，例如重试时间、来源模块、失败原因

#### 3.3 延迟关单链路

- 对接真实支付成功链路
- 增加支付成功后取消延迟消息或消费端跳过已支付订单的完整逻辑
- 进一步细化多实例部署下的并发消费和幂等控制
- 把库存回补逻辑整理为更统一的补偿方法

#### 3.4 Bloom Filter 链路

- 增加新增店铺、删除店铺时的增量同步维护逻辑
- 增加手动重建入口和定时重建任务
- 基于真实数据量重新评估 `expectedInsertions` 和误判率参数
- 将布隆过滤器方案扩展到更多热点查询场景

### 4. 测试与验证待完善

- 增加 Kafka 秒杀下单主链路集成测试
- 增加延迟关单与库存回补测试
- 增加布隆过滤器误判率和穿透拦截效果验证
- 补充压测，关注吞吐、响应时间、数据库压力和缓存命中率变化

当前已完成的定向测试：

- Kafka 下单 Consumer 消息委派与异常抛出测试
- 缓存删除补偿 Consumer 重试与重试上限测试

### 5. 服务部署待完善

如果要把当前能力完整联调或演示，还需要补齐以下环境和文档：

- Kafka 服务部署与 Topic 检查脚本
- Redis 服务部署，支撑 Lua、分布式锁、延迟队列、布隆过滤器
- MySQL 服务部署与初始化数据
- Docker Compose 或部署文档
- 本地联调说明，包括 Kafka、Redis、MySQL 的启动顺序和配置项说明

建议补充的部署类内容：

- `KAFKA_BOOTSTRAP_SERVERS` 配置说明
- `REDIS_HOST` / `REDIS_PASSWORD` 配置说明
- `DB_HOST` / `DB_USERNAME` / `DB_PASSWORD` 配置说明
- Topic 创建、消费者组校验和本地联调脚本

### 6. 建议的下一步开发顺序

建议按下面顺序继续完善：

1. 先补 Kafka 幂等补偿和消费失败治理，稳住秒杀主链路
2. 再补支付回调和超时关单闭环增强
3. 再补缓存补偿重试增强版
4. 最后补布隆过滤器增量维护、压测和部署文档

## 当前限制与扩展方向

当前实现更适合开发联调、学习演示和面试讲解，还不是完整生产级方案。  
后续可以继续往下扩展：

- Kafka 从单 broker 升级到多 broker KRaft 集群
- Redis 从单实例升级到更高可用部署
- 秒杀库存预热自动化
- 更完善的监控、告警和失败追踪
- 更真实的压测环境与基准数据
- 更强一致性的消息与补偿体系

## 辅助文档

如果你想继续看更细的原理、面试和部署说明，可以参考：

- [docker/compose/README.md](docker/compose/README.md)
- [interview/Redisson与Kafka项目理解强化.md](interview/Redisson与Kafka项目理解强化.md)
- [interview/Redisson 实现细节指南.md](interview/Redisson%20实现细节指南.md)

## 一句话总结

`LivPick` 当前已经从普通点评类练手项目演进为一个以 `Redis + Lua + Kafka + Redisson` 为核心的本地生活秒杀实战项目，重点展示了高并发秒杀、缓存治理、延迟任务和最终一致性方案在真实业务链路中的落地方式。
