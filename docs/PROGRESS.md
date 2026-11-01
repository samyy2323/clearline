# Progress

- [x] Phase 0: Maven multi-module skeleton, injectable clock (`libs/clock`)
- [x] Phase 1 (core): in-memory inverted index, hashed-LR pCTR with hot reload, first-price + GSP, A/B arms, deadline-aware pipeline
- [x] Phase 2 (core): Redis Lua leases, local spend, PID pacing, frequency caps (in-mem + Redis Lua), jqwik overspend invariant
- [ ] App layer: bid endpoint, campaign CRUD + JWT, tracker (HMAC, dedupe, fraud), SSE
- [ ] Simulator (open-loop, HdrHistogram) + real benchmark results
- [ ] Dashboard, ML training, compose/Docker, free deploy, docs, v1.0.0
