# Clearline: Lite edition (frozen snapshot, tag `lite-v0.9.1`)

The single-process version: bid gateway, auction engine, campaign admin with an in-process outbox, event tracker, simulator and
live stats in one Spring Boot app, with in-memory stores (Redis optional). It deploys free (Render / Koyeb / Fly) and runs on a laptop.

This folder is a read-only snapshot of what was built first. The full edition (Kafka, Postgres, ClickHouse, reporting service,
tracing, Kubernetes) is developed in the repository root and builds on the same modules. Do not edit files here.

Run it:  `docker compose -f deploy/compose.lite.yml up --build`  (or `make up-lite`)  ->  http://localhost:8080
