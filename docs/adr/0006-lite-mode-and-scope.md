# ADR 0006: Two editions, lite and full; what each contains and what is still not built

Status: accepted (updated for the full edition)

## Context
The project has to be deployable for free and runnable on a laptop, and it also has to show the real data architecture (Kafka,
Postgres, ClickHouse). Those pull in opposite directions: a free 512 MB host cannot run a broker and an OLAP database.

## Decision
One codebase, two editions selected by `exchange.mode` (profile `full` turns on the extra beans; interfaces keep the hot path identical):

| | Lite (`clearline-lite/`, tag `lite-v0.9.1`) | Full (repository root) |
|---|---|---|
| Campaign store and outbox | in memory, in-process relay | Postgres + Flyway, relay publishes to Kafka |
| Index updates | applied directly | compacted Kafka topic, replayed from the beginning by each engine |
| Events | in-memory aggregates | Kafka -> ClickHouse rollups, DLQ, load shedding |
| Reporting | in the app | separate reporting service over ClickHouse, JWT + tenant scoped |
| Budget ledger | in memory (Redis optional) | Redis Lua leases |
| Tracing | off | OpenTelemetry to Jaeger |
| Footprint | one 512 MB container (free hosts) | ~6-8 GB: compose, or Kubernetes manifests |

Services are modules of one deployable app plus the reporting service, not a full mesh of seven processes (the gateway, engine, campaign
and tracker logic share an in-process hot path on purpose).

## Still not built
Protobuf and a schema registry (JSON is used, ADR 0008); a WebFlux gateway (ADR 0005); Toxiproxy network-level chaos (ADR 0007);
a Bloom or count-min frequency tier; exactly-once delivery to ClickHouse; multi-engine reconciliation (ADR 0009); the stretch items
(deterministic replay, bid shading); a verified deployment on a live Kubernetes cluster (manifests render, they were not applied).

## Consequences
Everything claimed in the README is something that runs and is tested here, and the README states per feature which edition has it.
