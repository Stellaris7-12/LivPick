# LivPick MySQL-only 秒杀分支

当前分支只做一件事：

- 用纯 MySQL 完成秒杀下单链路，并把它作为后续优化方案的压测基线

## 1. 核心实现

秒杀入口：

- `POST /voucher-order/seckill/{voucherId}`

核心代码：

- [VoucherOrderServiceImpl](C:/Users/heyunhui/IdeaProjects/LivPick/src/main/java/com/livepick/service/impl/VoucherOrderServiceImpl.java:1)

当前方案的保证：

- 一人一单：业务查重 + `(voucher_id, user_id)` 唯一索引兜底
- 库存不超卖：条件更新扣减库存
- 事务一致性：查重、扣库存、插订单在同一事务中

压测专用身份注入：

- 请求头：`X-Benchmark-User-Id`
- 开关：`--app.benchmark.skip-login-check=true`

## 2. 去哪里看

如果你要复跑或看结果，优先看这两个文件：

- 压测总览：[benchmark-final/README.md](C:/Users/heyunhui/IdeaProjects/LivPick/benchmark-final/README.md:1)
- MySQL-only 专题报告：[mysql-only.md](C:/Users/heyunhui/IdeaProjects/LivPick/benchmark-final/reports/mysql-only.md:1)

## 3. 一句话结论

当前 `MySQL-only` 基线在本机压测下约为 `220~230 QPS`。并发继续提高时，吞吐基本封顶，但 RT 明显恶化；主瓶颈已经定位为 `tb_seckill_voucher` 热点库存行的数据库锁竞争，而不是 CPU、内存或 GC。
