# hm-dianping 项目分析

## 1. 项目定位

这是一个基于 Spring Boot 的点评类后端项目，业务场景接近“大众点评”：

- 用户手机号验证码登录
- 店铺信息查询与分类查询
- 探店笔记发布、点赞、热门榜
- 用户关注、共同关注
- 优惠券与秒杀券
- 高并发秒杀下单

项目整体是单体应用，不是微服务架构。它采用典型的分层式设计：

`Controller -> Service -> Mapper -> MySQL`

与此同时，Redis 在这个项目中不只是缓存，还承担了登录态、点赞、关注集合、GEO 附近搜索、签到、全局 ID 和秒杀异步队列等职责。

## 2. 技术栈

从 `pom.xml` 可以看出本项目的核心技术栈如下：

- Spring Boot 2.3.12.RELEASE
- Spring MVC
- MyBatis-Plus 3.4.3
- MySQL 5.x 驱动
- Redis
- Redisson
- Hutool
- Lombok

其中：

- Spring Boot 负责整体应用启动、依赖整合和 Web API 暴露
- MyBatis-Plus 负责数据库访问与分页
- Redis 负责高频读写场景和高并发控制
- Redisson 负责分布式锁能力

## 3. 工程结构

项目的主代码位于 `src/main/java/com/hmdp`，按职责分层：

- `controller`
  - 对外提供 REST 接口
- `service`
  - 定义业务接口
- `service/impl`
  - 实现具体业务逻辑
- `mapper`
  - MyBatis-Plus / Mapper 层
- `entity`
  - 数据库实体对象
- `dto`
  - 接口传输对象和统一返回体
- `config`
  - MVC、MyBatis-Plus、Redisson、异常处理等配置
- `utils`
  - Redis 工具、拦截器、分布式锁、正则工具、用户上下文等

资源文件位于 `src/main/resources`：

- `application.yaml`
  - 基础配置
- `db/hmdp.sql`
  - 初始化表结构和测试数据
- `seckill.lua`
  - 秒杀原子校验脚本
- `unlock.lua`
  - Redis 分布式锁解锁脚本

## 4. 核心框架设计

### 4.1 Web 层

项目使用 Spring MVC 暴露接口，请求由各个 `Controller` 处理，例如：

- `UserController`
- `ShopController`
- `BlogController`
- `FollowController`
- `VoucherController`
- `VoucherOrderController`

接口统一返回 `Result` 对象，便于前端统一处理成功、失败和数据结构。

### 4.2 持久层

项目使用 MyBatis-Plus：

- 通过 `ServiceImpl` 简化常见 CRUD
- 通过 `query()`、`update()` 等链式 API 提升开发效率
- 通过 `MybatisPlusInterceptor` + `PaginationInnerInterceptor` 实现分页

这意味着项目的大量数据库操作不需要手写复杂 SQL，只有个别复杂查询会放在 Mapper 或 XML 中。

### 4.3 Redis 作为核心基础设施

这个项目最有代表性的地方不在于普通 CRUD，而在于 Redis 的广泛使用。Redis 主要承担以下角色：

- 验证码存储
- 登录 token 存储
- 店铺缓存
- 空值缓存，防止缓存穿透
- 互斥锁 / 逻辑过期，降低缓存击穿风险
- GEO 附近店铺查询
- 点赞集合排序
- 关注集合交集计算
- Feed 流收件箱
- 用户签到位图
- Redis 自增全局 ID
- 秒杀库存、一人一单校验
- Stream 消息队列异步下单

可以说，这个项目的高性能设计几乎都围绕 Redis 展开。

### 4.4 登录态设计

项目没有采用传统 Session 登录，而是使用 Redis 保存登录用户信息：

1. 用户提交手机号和验证码
2. 服务端校验 Redis 中保存的验证码
3. 登录成功后生成 token
4. 将用户简要信息写入 Redis Hash
5. 前端后续请求携带 `authorization` 头
6. 拦截器读取 token，从 Redis 恢复用户信息
7. 用户信息写入 `ThreadLocal`
8. 后续业务代码通过 `UserHolder` 获取当前登录用户

这里使用了两个拦截器：

- `RefreshTokenInterceptor`
  - 负责解析 token、刷新 TTL、保存用户上下文
- `LoginInterceptor`
  - 负责拦截必须登录后才能访问的接口

这种设计比 Session 更适合前后端分离场景。

## 5. 核心业务功能

### 5.1 用户模块

用户模块提供以下能力：

- 发送验证码
- 验证码登录
- 查询当前登录用户
- 查询用户详情
- 用户签到
- 连续签到统计

其中签到功能使用 Redis Bitmap 实现：

- 某月某用户的签到记录存为一个位图
- 当日签到就是设置某一位为 `1`
- 连续签到统计通过 `BITFIELD` 读取位图后进行位运算

这是一种典型的 Redis 位图应用场景。

### 5.2 店铺模块

店铺模块包括：

- 根据 id 查询店铺详情
- 更新店铺信息
- 按名称分页查询
- 按类型分页查询
- 按经纬度查询附近店铺

这里有两个重点：

#### 店铺缓存

查询店铺详情时，优先从 Redis 读取，数据库作为兜底源。项目封装了 `CacheClient`，实现了三类缓存策略：

- 缓存穿透保护：缓存空值
- 缓存击穿保护：互斥锁重建
- 热点数据保护：逻辑过期

当前默认使用的是“缓存穿透保护”方案。

#### 附近店铺查询

项目将店铺坐标写入 Redis GEO 结构中，查询时根据经纬度和半径检索，再按距离排序后回查 MySQL 详情。

这使项目具备“附近商户”这类本地生活应用常见能力。

### 5.3 笔记 / 社交模块

笔记模块支持：

- 发布探店笔记
- 查询热门笔记
- 查询笔记详情
- 点赞 / 取消点赞
- 查询点赞用户 Top N
- 查询关注用户发布的笔记流

其核心实现方式如下：

- 点赞：
  - MySQL 维护点赞总数
  - Redis ZSet 保存点赞用户和点赞时间
- 热门笔记：
  - 按 `liked` 数倒序分页
- 关注推送：
  - 用户发笔记后，将笔记 id 推送到粉丝的收件箱
  - 收件箱使用 Redis ZSet 实现
- Feed 滚动分页：
  - 使用时间戳作为 score
  - 通过 `reverseRangeByScoreWithScores` 实现滚动分页

这是项目里社交关系和内容分发的核心逻辑。

### 5.4 关注模块

关注模块支持：

- 关注用户
- 取关用户
- 判断是否已关注
- 查询共同关注

实现方式是：

- MySQL 保存正式关注关系
- Redis Set 保存当前用户关注列表
- 共同关注通过两个 Set 的交集完成

这种做法兼顾了数据持久性和查询性能。

### 5.5 优惠券与秒杀模块

该模块包括：

- 查询店铺优惠券
- 新增普通券
- 新增秒杀券
- 抢购秒杀券

秒杀券是整个项目最具代表性的高并发场景。

## 6. 秒杀下单主链路

秒杀模块不是直接“请求一进来就操作数据库”，而是设计成“Redis 原子校验 + 异步下单”。

整体流程如下：

1. 用户发起秒杀请求
2. 服务端生成订单 id
3. 执行 `seckill.lua`
4. Lua 脚本在 Redis 中原子完成：
   - 判断库存是否充足
   - 判断用户是否重复下单
   - 扣减库存
   - 记录购买用户
   - 将订单消息写入 `stream.orders`
5. 如果 Lua 返回成功，接口直接返回订单 id
6. 后台单线程任务持续消费 `Redis Stream`
7. 消费到订单消息后，再真正落库到 MySQL

### 6.1 为什么这样设计

这样做有几个直接好处：

- Redis 单线程 + Lua 保证校验和扣减原子性
- 请求线程非常快，不需要同步阻塞数据库写入
- 异步化后能承受更高并发
- 利用 Stream 可以处理未确认消息和异常恢复

### 6.2 防止重复下单

即使已经在 Lua 中做过“一人一单”校验，落库时依然又做了一次保护：

- 按用户维度加 Redisson 分布式锁
- 查询数据库是否已有该用户该券的订单
- 再扣减数据库库存并保存订单

这属于典型的“双重保护”设计，避免极端并发或消息重复消费带来的问题。

## 7. 关键工具类

### 7.1 `CacheClient`

该类封装了项目的缓存通用能力，是整个项目非常重要的基础组件。

它的价值在于：

- 把缓存写入逻辑统一封装
- 把缓存穿透、击穿、逻辑过期方案统一封装
- 让业务代码不必重复处理缓存细节

### 7.2 `RedisIdWorker`

这是一个基于 Redis 的全局唯一 ID 生成器，核心思路是：

- 高位使用时间戳
- 低位使用 Redis 当日自增序列

它能生成趋势递增、全局唯一的 long 型 id，很适合订单号这类业务。

### 7.3 `UserHolder`

`UserHolder` 本质上是一个 `ThreadLocal<UserDTO>` 包装器，用于保存当前线程的登录用户信息。

业务代码无需每次显式传递用户对象，只需：

- 拦截器写入
- Service 中读取
- 请求完成后移除

这让登录态获取更加简洁。

## 8. 数据模型

从 `db/hmdp.sql` 可以看出，主要表包括：

- `tb_user`
  - 用户
- `tb_user_info`
  - 用户详情
- `tb_shop`
  - 店铺
- `tb_shop_type`
  - 店铺分类
- `tb_blog`
  - 探店笔记
- `tb_blog_comments`
  - 笔记评论
- `tb_follow`
  - 关注关系
- `tb_voucher`
  - 优惠券
- `tb_seckill_voucher`
  - 秒杀券
- `tb_voucher_order`
  - 优惠券订单

整体上是一个典型的“本地生活 + 社交 + 营销”活动后端模型。

## 9. 运行依赖与注意事项

从当前配置文件可以看出，项目依赖：

- MySQL 数据库
- Redis 服务

并且配置中已经写死了数据库和 Redis 连接信息，说明这个仓库当前更偏学习/demo 项目，而不是可直接上线的生产配置方式。

另外，秒杀模块运行前需要提前在 Redis 中创建 Stream 消费组，源码注释里已经明确提示：

```bash
XGROUP CREATE stream.orders g1 0 MKSTREAM
```

如果没有提前创建，秒杀订单消费者会报错。

## 10. 项目特点总结

这个项目最核心的价值不在于“表有多少、接口有多少”，而在于它集中展示了很多典型 Redis 实战方案：

- Redis 缓存穿透处理
- Redis 互斥锁
- 逻辑过期缓存重建
- Redis GEO
- Redis Bitmap
- Redis Set 交集
- Redis ZSet 排行与 Feed 流
- Redis Stream 异步消息
- Lua 原子脚本
- Redis 全局 ID
- Redisson 分布式锁

所以如果把它当成“一个 Spring Boot 练手项目”来看，重点不只是 CRUD，而是：

**如何把 Redis 深度融入业务系统，解决缓存、登录、高并发和社交数据结构问题。**

## 11. 一句话总结

`hm-dianping` 是一个基于 Spring Boot + MyBatis-Plus + MySQL + Redis 的点评类后端项目，重点演示了 Redis 在登录态、缓存优化、GEO 搜索、社交关系、签到统计和秒杀高并发场景中的实际用法。
