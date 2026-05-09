# LivPick（hm-dianping）Code Review 报告

> 评审依据：`.cursor/rules/code-cr-rule.mdc`（按 Critical / Warning / Info 分级，覆盖功能、性能、安全、可维护性、架构、测试维度）  
> 评审范围：当前工作区全量扫描（Maven 单模块，`src/main/java`、`src/main/resources`、`src/test/java`）  
> 评审结论：**2/5（风险偏高，存在线上事故级问题，需先做 P0 修复）**

## 评审概览

- **变更意图**：对现有项目进行全量静态代码审查，识别安全/稳定性/可维护性风险点并给出改进建议。
- **项目结构**：
  - **构建**：Maven（`pom.xml`），Spring Boot `2.3.12.RELEASE`
  - **入口类**：`src/main/java/com/hmdp/HmDianPingApplication.java`
  - **配置**：`src/main/resources/application.yaml`
  - **测试**：`src/test/java/com/hmdp/*`
- **影响范围**：
  - **安全面**：明文密码、验证码日志、匿名文件删除（疑似路径穿越）会直接影响数据与主机安全。
  - **稳定性面**：缓存互斥锁实现存在严重并发缺陷；后台线程池缺少生命周期治理。
  - **可维护性面**：版本偏旧、硬编码路径、异常处理粗粒度增加升级与排障成本。

## 🔴 Critical（必须修复）

### 1) 明文凭证泄露（DB/Redis 密码写入配置）

- **位置**：`src/main/resources/application.yaml:6-20`
- **问题描述**：数据库与 Redis 密码明文写入仓库（`password: heyunhui2856`、`password: redis1234`）。
- **影响**：凭证一旦泄露将导致数据库/Redis 被直接入侵；同时该文件易进入构建产物与镜像，扩大泄露面。
- **建议**：
  - 立即**轮换**已泄露密码（DB/Redis）。
  - 使用环境变量/密钥管理：如 `${SPRING_DATASOURCE_PASSWORD}`、`${SPRING_REDIS_PASSWORD}`。
  - 分环境配置（dev/test/prod），并启用 secret scan（提交前/CI）。

### 2) Redisson 连接信息硬编码（含 Redis 密码）

- **位置**：`src/main/java/com/hmdp/config/RedissonConfig.java:12-18`
- **问题描述**：`setAddress("redis://192.168.11.130:6379").setPassword("redis1234")` 写死。
- **影响**：凭证泄露 + 环境不可移植（开发/测试/生产切换困难），也会导致误连生产或错误环境。
- **建议**：迁移到 `application.yaml` 并通过配置注入；密码走环境变量/密钥中心。

### 3) 未认证文件删除接口 + 潜在路径穿越删除任意文件

- **位置A（登录放行）**：`src/main/java/com/hmdp/config/MvcConfig.java:20-33`
- **位置B（删除实现）**：`src/main/java/com/hmdp/controller/UploadController.java:37-45`
- **问题描述**：
  - `MvcConfig` 将 `/upload/**` 排除登录校验（包含删除接口）。
  - `deleteBlogImg(name)` 将用户输入直接拼接为文件路径：`new File(IMAGE_UPLOAD_DIR, filename)`，未做规范化/白名单校验。
  - 使用 `GET` 进行删除操作（语义不当，且更容易被误触发/被 CSRF 利用）。
- **影响**：匿名用户可删除上传目录文件；若路径穿越成立，可升级为删除服务端任意可访问文件（严重安全事故）。
- **建议**：
  - 删除接口改为 **需要登录** 且按业务做鉴权（至少校验资源归属）。
  - HTTP 方法改为 `DELETE`（或 `POST` + CSRF 防护）。
  - 对 `filename` 严格白名单（禁止 `..`、禁止绝对路径/盘符、限制后缀与目录），并对最终 `canonicalPath` 做目录前缀校验。

### 4) 缓存互斥锁实现存在“未持锁也解锁/删锁”并发缺陷

- **位置**：`src/main/java/com/hmdp/utils/CacheClient.java:120-167`
- **问题描述**：
  - `queryWithMutex()` 中 `tryLock()` 失败会 `return queryWithMutex(...)`（递归重试），但 `finally` 一定执行 `unlock(lockKey)`（`162-164`）。
  - 结果是：**当前线程未获得锁也会删除锁 key**，破坏互斥语义。
- **影响**：并发下会导致缓存击穿/重建风暴、脏数据、数据库被打爆（稳定性事故级）。
- **建议**：
  - 仅在成功获取锁后才释放锁（并用 `boolean locked` 标记）。
  - 避免递归重试（改为循环 + 最大重试/超时/退避）。
  - 锁 value 使用唯一标识，解锁时校验（或直接使用 Redisson 分布式锁）。

## 🟡 Warning（建议修复）

### 1) 短信验证码写入日志（敏感信息泄露）

- **位置**：`src/main/java/com/hmdp/service/impl/UserServiceImpl.java:48-65`
- **问题描述**：`log.debug("发送短信验证码成功，验证码：{}", code);` 直接输出验证码。
- **影响**：任何能读取日志的人都可能接管账号；日志采集链路会放大暴露面。
- **建议**：禁止记录验证码；如需排障，仅记录发送结果、手机号脱敏与 traceId。

### 2) 全局异常处理过于粗粒度，易吞掉业务异常

- **位置**：`src/main/java/com/hmdp/config/WebExceptionAdvice.java:10-16`
- **问题描述**：仅捕获 `RuntimeException` 并统一返回“服务器异常”，缺少统一错误码与常见异常（参数校验/方法不支持等）处理策略。
- **影响**：线上定位困难；前端无法区分错误类型；可能导致错误归类/不可观测。
- **建议**：补齐异常体系与错误码；增加对校验异常、404/405、数据访问异常等的分类处理，并控制日志字段避免敏感信息。

### 3) Redis Stream pending ACK key 不一致（疑似 bug）

- **位置**：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java:93-118`
- **问题描述**：消费 stream 使用 `queneName = "stream.orders"`，但 pending 处理 ACK 使用 `acknowledge("s1", "g1", ...)`（第 114 行），与 stream key 不一致。
- **影响**：pending 消息可能无法正确 ACK，导致重复消费/积压。
- **建议**：ACK 使用同一 stream key；补充用例验证 pending 恢复与幂等逻辑。

### 4) 线程池/后台线程生命周期治理不足

- **位置**：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java:54-60`、`src/main/java/com/hmdp/utils/CacheClient.java:26`
- **问题描述**：直接使用 `Executors.newSingleThreadExecutor()` / `newFixedThreadPool(10)` 创建线程池，缺少线程命名、拒绝策略、优雅关闭；长期运行的 `while(true)` 缺少退出与健康治理。
- **影响**：无法平滑停机；资源泄露或静默失败后持续不可用；线程排障困难。
- **建议**：使用 Spring 管理的线程池（如 `ThreadPoolTaskExecutor`）或自定义 `ThreadFactory`；增加 shutdown 逻辑与监控指标；异常重试加退避。

### 5) 硬编码本地路径导致不可移植

- **位置**：`src/main/java/com/hmdp/utils/SystemConstants.java:4-8`
- **问题描述**：`IMAGE_UPLOAD_DIR` 写死到 `D:\lesson\...`。
- **影响**：生产/容器环境不可用；部署强耦合；也容易造成路径探测与权限问题。
- **建议**：改为配置项并按环境注入；限制上传目录权限并做好磁盘空间治理。

### 6) 依赖版本偏旧（安全与兼容风险）

- **位置**：`pom.xml:5-86`
- **问题描述**：Spring Boot `2.3.12.RELEASE`、MySQL Connector `5.1.47` 等较旧；Redis 相关依赖手动排除与固定版本，升级复杂度更高。
- **影响**：已知漏洞修复滞后；与 JDK/中间件新版本兼容风险。
- **建议**：制定升级路径与回归计划（优先升级到受支持版本线），并梳理 Redis/MyBatis-Plus/Redisson 兼容矩阵。

## 🔵 Info（优化建议）

### 1) 登出接口未实现

- **位置**：`src/main/java/com/hmdp/controller/UserController.java:60-64`
- **建议**：实现 token 失效（删除 Redis token/刷新 token），并补充接口测试。

## 风险评估与修复建议

- **生产风险等级**：**高**
- **建议修复时间**：
  - **P0（立即）**：移除明文凭证（含配置与代码硬编码）并轮换；修复 `/upload` 删除鉴权与路径校验；修复 `CacheClient.queryWithMutex()` 未持锁解锁缺陷。
  - **P1（本周）**：清理验证码日志；完善异常处理体系；修复 Stream ACK key；规范线程池与优雅停机。
  - **P2（后续）**：依赖升级与配置治理、消除硬编码路径、补齐测试覆盖与可观测性。

