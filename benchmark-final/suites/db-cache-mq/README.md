# DB-Cache-MQ Suite

This suite is prepared for the `MySQL + Redis + Kafka` architecture.

Included in this implementation round:
- benchmark-only management endpoints in the application
- seckill correctness metrics exposed by `/benchmark/metrics`
- schema patch requirement for timeout fallback scanning
- directory structure for `standard` and `flash-sale`

Planned next:
- finalize JMeter `.jmx` scenarios for standard and flash-sale
- add runtime monitor scripts and aggregate summary output
- produce the formal `db-cache-mq` benchmark report
