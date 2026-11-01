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
- [ ] Not done: JFR flame graphs, allocation work, Helm/kind, Kafka/ClickHouse/Postgres (see ADR 0006), real Render deployment
