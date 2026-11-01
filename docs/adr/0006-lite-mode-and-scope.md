# ADR 0006: Lite mode, and what this build does not include

Status: accepted

## Context
The project has to be deployable for free and runnable on a laptop. Kafka, ClickHouse and Postgres cannot run on a free
512 MB tier, and a 7-service microservice mesh adds operational surface without adding anything an interview can probe
that the lease, pacing and auction code do not already show.

## Decision
One Spring Boot application contains the bid gateway, auction engine, campaign admin (with outbox), event tracker, simulator and
live stats, built from separate library modules (`clock`, `budget`, `frequency-cap-lib`). Stores are interfaces with an
in-memory implementation (lite) and a Redis implementation (compose). The ML trainer is a separate offline Python process.

## Not implemented (stated plainly)
Kafka and Schema Registry; ClickHouse rollups and the reporting service (the app keeps in-memory aggregates); Postgres and
Flyway; Protobuf (JSON is used); OpenTelemetry tracing across services (there is one service); Helm/kind; WebFlux; Bloom or
count-min frequency tier; the stretch items. Chaos faults are injected in-process, not by Toxiproxy (ADR 0007).

## Consequences
Everything claimed in the README is something that runs and is tested here. Splitting the app along the existing module and
interface seams is mechanical if a multi-service deployment is wanted.
