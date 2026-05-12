# Docker Compose 快速说明

这套文件用于本地启动当前项目联调所需的中间件：

- Redis：单实例
- Kafka：单 broker、KRaft 模式

## Docker Compose 是什么

可以把 `docker compose` 理解成一个轻量级的多容器管理工具。  
它适合：

- 本地开发
- 测试联调
- 小规模环境验证

它不等同于 Kubernetes 这类完整生产级编排平台，但把它称为“轻量级容器编排 / 多容器管理工具”是没问题的。

## 这套配置会启动几个容器

当前会启动 2 个容器：

1. `livpick-redis`
2. `livpick-kafka`

也就是说：

- 不是一个容器同时运行 Redis 和 Kafka
- 而是两个容器组成一个本地中间件联调环境

## 常用命令

在本目录下执行，默认需要 `.env` 文件。

启动：

```powershell
docker compose --env-file .env -f docker-compose.middleware.yml up -d
```

查看状态：

```powershell
docker compose --env-file .env -f docker-compose.middleware.yml ps
docker ps
```

停止但保留容器：

```powershell
docker compose --env-file .env -f docker-compose.middleware.yml stop
```

关闭并移除容器：

```powershell
docker compose --env-file .env -f docker-compose.middleware.yml down
```

关闭并删除卷：

```powershell
docker compose --env-file .env -f docker-compose.middleware.yml down -v
```

查看日志：

```powershell
docker logs livpick-redis
docker logs livpick-kafka
docker logs -f livpick-kafka
```

进入容器：

```powershell
docker exec -it livpick-redis redis-cli
docker exec -it livpick-kafka bash
```

> 提示  
> 当前 Kafka 容器内的管理脚本通常不在默认 `PATH` 中。  
> 例如查看 topic 时，建议直接使用完整路径：
>
> ```powershell
> docker exec -it livpick-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
> ```

## 何时保留容器，何时删除容器

你当前真正需要区分的其实是 3 种情况：

1. `stop`
2. `down`
3. `down -v`

### 1. 什么时候用 `stop`

`stop` 会：

- 停止容器进程
- 保留容器
- 保留 volume 数据

适合场景：

- 你今天暂时不用 Redis / Kafka 了，想节省内存和 CPU
- 你后面还会继续联调，希望下次直接再启动
- 你不想删除容器定义，只是先停掉运行状态

可以理解成：

- “先关机，但机器还在”

### 2. 什么时候用 `down`

`down` 会：

- 停止容器
- 删除容器
- 删除 compose 创建的网络
- 保留 volume 数据

适合场景：

- 这轮联调结束了，想把容器收掉
- 你不需要保留当前容器实例本身
- 但你还想保留 Redis / Kafka 的数据，下次再起时继续用

可以理解成：

- “把机器拆掉，但硬盘还留着”

### 3. 什么时候用 `down -v`

`down -v` 会：

- 停止容器
- 删除容器
- 删除网络
- 删除 volume

适合场景：

- 你想彻底重置本地中间件状态
- 你不再需要保留 Redis 数据或 Kafka 日志数据
- 你想从一个完全干净的环境重新开始

可以理解成：

- “机器和硬盘一起清空”

### 简单建议

- 当天不用了，但后面还会继续联调：用 `stop`
- 这轮联调结束，想收掉容器但保留数据：用 `down`
- 想彻底恢复初始状态：用 `down -v`

## volume 映射有什么作用

当前 `docker-compose.middleware.yml` 里用了两个命名卷：

- `redis-data`
- `kafka-data`

它们的作用是把容器里的关键数据目录持久化出来，避免容器删除后数据一起丢失。

### Redis 的 volume

Compose 中对应的是：

```yaml
volumes:
  - redis-data:/data
```

作用：

- 保存 Redis AOF / RDB 等持久化数据
- 容器删除后，只要卷还在，Redis 数据还能恢复

### Kafka 的 volume

Compose 中对应的是：

```yaml
volumes:
  - kafka-data:/var/lib/kafka/data
```

作用：

- 保存 Kafka 日志段、topic 数据、offset 等元数据
- 容器删除后，只要卷没删，topic 和消息数据仍可保留

### 为什么这很重要

如果没有 volume 映射：

- 你执行 `down` 或容器异常重建后
- Redis 库存、布隆过滤器、缓存数据可能丢失
- Kafka topic 和消息日志也可能丢失

所以：

- 想保留本地联调状态时，不要随便 `down -v`
- `down` 可以删容器但保留数据
- `down -v` 才是彻底重置

## Volume 在宿主机的存储位置

Docker 的命名卷（named volume）默认存储在宿主机的 Docker 数据目录下：

```bash
/var/lib/docker/volumes/<volume_name>/_data
```

以你 compose 中的两个卷为例：

- `redis-data` → `/var/lib/docker/volumes/redis-data/_data`
- `kafka-data` → `/var/lib/docker/volumes/kafka-data/_data`

你可以通过以下命令查看具体位置：

```bash
docker volume inspect redis-data
# 输出中会显示 Mountpoint 字段
```

## 会占很大空间吗？

**取决于数据量和运行时长**，可能占用从几十 MB 到数 GB 甚至更多。

| 组件 | 典型数据 | 膨胀可能性 |
|------|---------|------------|
| **Redis** | AOF 日志、RDB 快照 | 较低，如果开启持久化且写入频繁，体积随时间增加；可配置淘汰策略和 `auto-aof-rewrite` 控制 |
| **Kafka** | 消息日志、offset、索引 | 较高，消息保留时间越长、topic 越多、写入量越大，占用越大。可通过 `log.retention.hours/bytes` 等参数清理旧数据 |

### 监控与清理

你可以随时查看卷的真实大小：

```bash
# 查看所有卷的大小
docker system df -v

# 或者直接 du 查看某个卷
sudo du -sh /var/lib/docker/volumes/redis-data/_data
```

**不要让卷无限增长**：  
- 开发联调环境建议定期执行 `docker compose down -v` 来彻底重置数据  
- 生产环境务必配置合理的 Kafka 保留策略和 Redis maxmemory + 淘汰策略

> 提示：如果宿主机磁盘空间紧张，可以考虑将卷迁移到外部存储或限制数据保留时长。

## 说明

更完整的部署流程、topic 说明、库存预置、业务闭环验证步骤已经统一收敛到根目录 [README.md](../../README.md) 的“本地部署与启动”和“业务闭环验证”部分。  
这里仅保留最基础的 Docker 概念和常用命令，避免重复。
