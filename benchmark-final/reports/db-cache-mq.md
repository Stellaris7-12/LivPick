# DB-Cache-MQ Report

## Status

This report is now ready for the next benchmark execution round.

Implemented in the application:
- asynchronous seckill order flow with pending-send retry
- timeout close flow that restores both stock and purchase eligibility
- cancelled-order reuse for re-seckill under the existing unique index
- benchmark endpoints:
  - `GET /benchmark/metrics`
  - `POST /benchmark/admin/metrics/reset`
  - `POST /benchmark/admin/seckill/reset`
  - `GET /benchmark/admin/mq/drain-status`

## Metrics To Compare

Primary end-to-end comparison metrics:
- API accepted requests
- Lua stock rejects and duplicate rejects
- Kafka send success/failure
- pending-send retry count and rollback count
- consumer created/reactivated/duplicate/failure count
- timeout close count and pay success count
- pending backlog and drain completion state

## Next Execution Checklist

Run the application with JDK 11 and benchmark enabled:

```text
LIVPICK_BENCHMARK_ENABLED=true
```

Then complete:
- `standard`: baseline, oversell, one-user-one-order
- `flash-sale`: burst and sustain
- monitor CPU, Redis ops, MySQL row lock waits, Kafka backlog/drain time
- compare against `mysql-only` and `db-cache` on the same machine and workload
