# ADR 0008: Events go app -> Kafka -> ClickHouse, with load shedding, a dead-letter topic and flagged-not-dropped fraud

Status: accepted (full edition)

## Context
Tracking events (win, impression, click) must reach an analytics store without ever slowing or breaking an auction, and the analytics
must be queryable per minute by campaign, publisher, geo, device and pricing arm.

## Decision
- **Producer side (`KafkaEventSink`).** The tracker only offers an event to a bounded queue (50,000). A single sender thread drains it
  into an async producer with `max.block.ms=200`, so a dead broker costs that thread 200 ms per attempt and never a request thread.
  A full queue drops and counts the event (load shedding). An event whose delivery fails after the producer's retries is re-sent to
  `ad-events.dlq` with the error in a header; if that also fails it is counted as lost. Counters are exported on the System health page.
- **Flagged events are shipped, not discarded.** Duplicates, bot user agents, click floods and clicks without an impression carry a
  `flag` and go through the same topic, so fraud is measurable. Rollups only count `flag = ''`; a second view counts flags per minute.
- **ClickHouse side (`deploy/clickhouse/init.sql`).** A Kafka-engine table consumes `ad-events`; materialized views fan out to a raw
  MergeTree log (30-day TTL) and to `rollup_minute`, an `AggregatingMergeTree` keyed by (campaign, minute, publisher, geo, device, arm)
  holding `sumState` aggregates (90-day TTL). Reads use `sumMerge`, which is correct whether or not background merges have happened.
- **Format.** JSON (`JSONEachRow`), not Protobuf with a schema registry. The schema is one record (`AdEvent`) mirrored by the
  ClickHouse DDL and checked by tests; moving to Protobuf is a serializer swap on both ends.

## Verified
Live compose run: after traffic stopped and the pipeline drained, the app's billing ledger and ClickHouse's billed spend agreed to
the micro (apart from two hand-injected test events, accounted for), with 0 dropped, 0 lost, 0 dead-lettered. Unit tests cover delivery,
DLQ routing, shedding when the sender is stuck, and an unreachable broker (`KafkaEventSinkTest`). `ClickHouseRollupIT` runs the real
DDL against a ClickHouse container (Kafka table swapped for a Null table so inserts trigger the views).

## Consequences
At-least-once from producer to broker (acks=1, no idempotence for throughput) means a retried batch could produce a duplicate row in
the raw log; billed spend is protected by event-id dedupe before the event is ever produced, and ClickHouse is treated as a reporting
store, not the ledger. Exactly-once would need idempotent producers plus a dedupe key in ClickHouse; not done.
