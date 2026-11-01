# Clearline architecture

## Shape of the system

```
                       signed URLs (win / imp / click)
   ad request             ┌──────────────────────────────────────────────┐
  ───────────────►  Gateway ──► Auction engine ──► response + tracking URLs
                    (shed,        retrieval  10 ms   │
                     validate)    scoring    10 ms   │ lease spend (local, no network)
                                  budget/freq 10 ms  ▼
                                      │         LeaseManager ◄── async ──► Lease store (Redis Lua | in-memory)
                                      │ index          ▲
                                      ▼                │ settle / renew / release
                               IndexHolder ◄── outbox relay ◄── Campaign service (CRUD, JWT roles)
   browser / app  ──► Tracker (HMAC verify, dedupe, fraud flags) ──► Billing ledger ──► event log (JSONL)
                                                                                    └─► ml/train.py ──► weights.json ──► hot reload
   Dashboard (React) ◄── SSE (fetch stream, bearer token) ◄── Stats sampler (1 Hz, HdrHistogram) ◄── Micrometer / Prometheus
```

In the shipped build all of the boxes on the left live in one Spring Boot process, built from separate modules
(`libs/clock`, `libs/budget`, `libs/frequency-cap-lib`, `services/auction-engine`). See ADR 0006 for what that means and what is not built.

## Hot path vs async path

| Hot path (per ad request) | Async path |
|---|---|
| Validate, shed if over `MAX_IN_FLIGHT` | Lease renewal, settle heartbeat, release (500 ms tick) |
| Index retrieval (bitset ANDs, no allocation per campaign) | Campaign changes via outbox relay (100 ms) |
| pCTR score per candidate (hashed LR, one `exp`) | Event log writer (bounded queue, drops when full) |
| Top-K, frequency-cap batch (1 Lua call, fail open) | Frequency-cap writes (bounded queue) |
| Pacing gate, per-slot award, local lease debit | Pacing controller update (500 ms) |
| Sign tracking URLs (HMAC) | Stats sampler, SSE fan-out, model reload poll (5 s) |

## Latency budget

Gateway 5 ms, retrieval 10 ms, scoring 10 ms, budget/frequency 10 ms, slack 15 ms (50 ms total). Each engine stage has its
own deadline and the whole pipeline has a 35 ms ceiling; a miss returns no-bid (`TIMEOUT`), never a late bid. Per-stage timings
are exported as Micrometer timers (`auction.stage.latency{stage}`) and shown on the System health page.

## Budget safety in one paragraph

Engines never debit Redis per bid. They hold leases and spend locally (ADR 0001). True spend is bounded by
`budget + lease_size x (instances + crashes)`; with graceful shutdown and prompt settles the slack is at most the spend since the
last heartbeat. A PID-style pacing controller (rate feed-forward plus PI trim on the error to the target curve) throttles each
campaign's participation so budget is spread across its flight instead of burning at the start.

## Failure modes

| Failure | Behaviour | Where it is tested |
|---|---|---|
| Redis down | Engine keeps spending its local lease, then fails closed (no budget, no bid). Frequency cap fails open and flags `degraded`. | `LeaseManagerTest`, `FrequencyCapTest`, `bench/chaos.sh` |
| Redis slow | Renewals lag (async); frequency-cap lookup adds latency on the hot path (visible in chaos plot) | `bench/chaos.sh` |
| Engine instance crash | Lease expires; `granted - reported` returns to the pool. Stranded budget at most one lease per instance until expiry. | `OverspendInvariantProperties` |
| Slow stage | Per-stage deadline, returns no-bid | `AuctionEngineTest` |
| Overload | Load shedding at `MAX_IN_FLIGHT` (204 + `X-No-Bid-Reason: SHED`), optional per-IP rate limit | `BidGateway` |
| Event sink slow | Bounded queue, drop and count, auctions unaffected | `EventLog`, `ResilientFrequencyCap` |
| Duplicate / replayed events | De-duplicated by event id; billed at most once | `BillingLedger` tests, load run drift = 0 |
| Forged / expired tracking URL | 400 / 410, counted as rejected | HTTP checks, `TrackerService` |
| Click fraud | Flagged and counted, never billed: bot UA, click without impression, per-user and per-IP flood | simulator injection |
| Bad model file | Previous weights keep serving | `StatsController.pollModel` |

## Security

JWT (HS256, 8 h) with roles `admin` and `advertiser`; advertisers only see their own campaigns and reports (other tenants' ids
return 404, not 403). Inputs are validated at the gateway and on campaign writes. Secrets come from environment variables
(development defaults are logged as a warning). Tracking tokens are HMAC-signed with the event type in the MAC and an expiry.
