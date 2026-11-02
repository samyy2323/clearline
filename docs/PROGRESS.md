# Progress

- [x] Phase 0: Maven multi-module skeleton, injectable clock (`libs/clock`)
- [x] Phase 1 (core): in-memory inverted index, hashed-LR pCTR with hot reload, first-price + GSP, A/B arms, deadline-aware pipeline
- [x] Phase 2 (core): Redis Lua leases, local spend, PID pacing, frequency caps (in-mem + Redis Lua), jqwik overspend invariant
- [x] App layer: bid endpoint, campaign CRUD + JWT + outbox, tracker (HMAC, dedupe, fraud), SSE stats, in-process behavior simulator, fault injection
- [x] Open-loop load generator (HdrHistogram, raw sockets), k6 script, baseline run saved in bench/results
- [x] Dashboard (React, TanStack Query, ECharts): splash, login, performance, campaigns CRUD, pricing A/B, system health, simulator
- [x] ML: offline trainer with Java/Python hashing parity test, hot reload verified live, convergence saved in bench/results/learning
- [x] Deploy packaging: Dockerfile, compose (+lite), Prometheus, Grafana, render.yaml, Makefile
- [x] Chaos run (before/after circuit breaker), config experiments, optimization notes, README
- [x] Full edition: Postgres outbox, Kafka (campaign feed + events, DLQ, shedding), ClickHouse rollups, reporting service, reconciler, tracing, k8s manifests, integration tests
- [ ] Not done: Protobuf/schema registry, WebFlux, Toxiproxy, Bloom tier, exact reconciliation credit, live k8s test, quiet-machine latency re-run of the profile fixes, a real deployment
