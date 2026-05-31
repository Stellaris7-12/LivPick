# Benchmark 目录说明

本目录只存放压测源码资产、执行脚本、SQL 初始化脚本和正式报告。

运行产物不应写入这里。所有压测结果目录、JTL、dashboard、监控 CSV 和日志统一输出到项目根下：

```text
target/benchmark/
```

如果在 `benchmark/` 下看到 `target/`，那是误放的运行产物，应清理掉。

## 当前专题

- [MySQL-only 专题](./reports/mysql-only.md)
  当前统一覆盖 standard 与 flash-sale。
- [DB-Cache 专题](./reports/db-cache.md)
  当前为本仓库的核心 benchmark 报告，已合并 standard 与 flash-sale。
- [架构横向比较](./reports/architecture-comparison.md)
  当前统一对照 `mysql-only` 与 `db-cache`，便于后续补入 `db-cache-mq`。
- [DB-Cache-MQ 专题](./reports/db-cache-mq.md)
  当前仅保留规划入口，后续补充。

## 套件结构

```text
benchmark-final/
  reports/
  suites/
    mysql-only/
      standard/
      flash-sale/
    db-cache/
      standard/
      flash-sale/
    db-cache-mq/
```

说明：

- `standard` 表示常规基准、超卖、一人一单、缓存命中等场景。
- `flash-sale` 表示更贴近真实秒杀业务的“海量需求 + 极低库存”高反差场景。
- `db-cache-mq` 当前尚未补齐脚本与报告，因此只保留占位目录。

## 当前权威结果目录

`mysql-only`：

- `target/benchmark/jmeter/run-20260527-000448`
- `target/benchmark/jmeter/run-20260527-113409`
- `target/benchmark/jmeter/run-20260527-114025`
- `target/benchmark/jmeter/run-20260527-114347`
- `target/benchmark/jmeter/flash-sale/mysql-only/run-20260531-193947`

`db-cache`：

- `target/benchmark/jmeter/db-cache/run-20260529-114434`
- `target/benchmark/jmeter/flash-sale/db-cache/run-20260530-110438`

## 使用原则

- 看结论时，优先从 `benchmark-final/reports/*.md` 进入。
- 跑脚本时，统一从 `benchmark-final/suites/.../run-jmeter-benchmark.ps1` 进入。
- 需要追原始证据时，再回到 `target/benchmark/...` 查看场景级产物。
