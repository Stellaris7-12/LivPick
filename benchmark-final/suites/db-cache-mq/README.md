# DB-Cache-MQ Suite

This suite is prepared for the `MySQL + Redis + Kafka` architecture.

This round treats `flash-sale-5k-100/500` as the primary evidence for MQ peak shaving.

Current suite coverage:
- `standard`
  - correctness and auxiliary baseline
  - cache-penetration OFF vs BLOOM_NULL
- `flash-sale`
  - main seckill comparison scenarios
  - consumer pause and backlog recovery
- `timeout-latency`
  - `FALLBACK_ONLY` vs `DELAY_QUEUE_FALLBACK`

Interpretation rule:
- use `flash-sale` as the primary conclusion
- use large-stock `baseline` only as supporting context
