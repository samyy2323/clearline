# Clearline

A real-time ad exchange. Advertisers manage campaigns in a dashboard; each ad request is scored, priced and awarded in memory in well
under the 50 ms budget; campaign budgets are spent from local leases of an atomic Redis ledger so overspend is bounded and measured;
tracking events are signed, de-duplicated and streamed through Kafka into ClickHouse for per-minute reporting; and a click model is
trained offline and hot-reloaded into the running engine.

> A portfolio project. Traffic is synthetic, brand names in the demo data are labels only (no logos, no real performance data), and every
> number below comes from a run whose raw output is saved in `bench/results/`.

## Two editions of one codebase

| | **Lite** | **Full** |
|---|---|---|
| Where | [`clearline-lite/`](clearline-lite/LITE-EDITION.md) (frozen snapshot, tag `lite-v0.9.1`) and this repo with `exchange.mode=lite` | this repo, `exchange.mode=full` |
| Campaigns + outbox | in memory | **Postgres + Flyway**, transactional outbox |
| Index updates | applied in process | **Kafka** compacted topic, replayed by every engine |
| Events | in-memory aggregates | **Kafka -> ClickHouse** rollups, DLQ, load shedding |
| Reporting | in the app | separate **reporting service** over ClickHouse |
| Tracing | off | **OpenTelemetry -> Jaeger** across app and reporting |
| Footprint | one 512 MB container, deploys free | about 6-8 GB: `docker compose` or Kubernetes |

## Quick start

```bash
make up-full     # full stack in Docker: app :8080, reporting :8081, Jaeger :16686, Prometheus :9090, Grafana :3000
make up-lite     # one small container, in-memory stores
make demo        # build + run the app, open the dashboard, run the compressed day (24 h in 20 min)
make test        # unit, jqwik property and Testcontainers integration tests (needs Docker)
make bench       # open-loop benchmark;  make chaos  fault injection under load;  make profile  JFR profile
make train       # offline pCTR training;  make k8s-render  render the Kubernetes manifests
```

Needs Java 21, Maven, Node 22, Docker, Python 3.10+ (training and plots). On Windows use Git Bash. Development logins: `admin` /
`admin123`, and `advertiser1` to `advertiser8` / `advertiser123` (Nike, Samsung, Spotify, Zomato, HDFC Bank, Hyundai, Booking.com, Nykaa).
Set `ADMIN_PASSWORD`, `ADVERTISER_PASSWORD`, `JWT_SECRET`, `HMAC_SECRET` for anything real.

## What is built

- **Auction.** OpenRTB-lite requests; in-memory inverted index (geo, device, segment, category, daypart); eCPM = bid x pCTR with a hashed
  logistic regression scored in process; floors, size matching, one winner per slot; first-price and GSP selectable or A/B-split with
  revenue per arm; per-stage deadlines that turn a slow stage into a no-bid, never a late bid.
- **Budget control.** Lease-based spend: atomic Lua scripts, lock-free local spend, async renew/settle, graceful release, expiry reclaim
  with late-spend tombstones; jqwik-tested overspend bound. PID-style pacing with an injectable clock. **Reconciliation** compares booked
  spend with ClickHouse-confirmed spend, emits a drift metric and **exactly** credits back spend that can no longer be billed, using the booking time carried in every event ([ADR 0009](docs/adr/0009-reconciliation-and-credit.md)).
- **Frequency caps.** Sliding window of time buckets; a Redis batch lookup is one Lua call; fails open and is guarded by a Resilience4j
  circuit breaker that also trips on slow calls. An optional count-min tier answers most lookups locally (a 24 MiB sketch cut Redis lookups to 15% of checks with 0 wrong answers; [ADR 0010](docs/adr/0010-count-min-frequency-tier.md)).
- **Events (full).** Signed, expiring win/impression/click URLs; dedupe; fraud flags (bots, click-without-impression, floods, duplicates)
  that are shipped and counted, never silently dropped; bounded buffer, dead-letter topic and load shedding in front of Kafka ([ADR 0008](docs/adr/0008-kafka-clickhouse-event-pipeline.md)).
- **Analytics (full).** ClickHouse Kafka-engine table, materialized views, `AggregatingMergeTree` per-minute rollups by campaign,
  publisher, geo, device and pricing arm; reporting service with JWT tenant scoping and bound query parameters; SSE stream.
- **Learning loop.** Offline Python trainer (log loss, AUC, calibration), atomic weight export, hot reload; a Java test proves training and
  serving hash identically.
- **Security.** JWT roles (admin, advertiser) with tenant isolation, input validation, per-IP rate limit and load shedding, secrets from env.
- **Dashboard.** React + TypeScript + TanStack Query + ECharts, light and dark theme: performance and pacing, campaign CRUD, pricing experiment, system health
  (the proof screen), simulator with failure injection, and (full) a Reports page from ClickHouse.
- **Live auction page (admin).** A mock publisher page whose ad slots change as real auctions resolve: each sampled auction is shown with every bidder's bid, predicted CTR
  and score, why each rival lost or was filtered out (frequency cap, pacing, floor, budget), the winner and the price it pays (the page's explanation text is computed from the same
  numbers as the engine's second-price rule). Creatives are original brand-coloured designs, not copied logos. Advertisers cannot open it (it shows rivals' bids).
- **Observability.** Micrometer + Prometheus, provisioned Grafana dashboard, OpenTelemetry traces to Jaeger.
- **Simulator.** Open-loop HTTP load generator (HdrHistogram, seeded, measures from intended send time, reports its own CPU) plus an
  in-process behavior simulator with hidden true CTR, duplicate clicks, click floods and bots; a k6 script for cross-checking.
- **Kubernetes.** Manifests with an HPA for the autoscaling demo (`deploy/k8s`).

## Architecture

```
 ad request -> Gateway -> Auction engine (index -> score -> freq cap -> pacing -> lease spend) -> response + signed URLs
                              ^ index                  | local lease spend (no network)         |
                              |                        v                                        v browser
        Kafka campaign-events (compacted) <- outbox relay <- Postgres (campaigns + outbox)     Tracker (HMAC, dedupe, fraud flags)
                                                                  Redis (Lua leases, freq caps)       |
                                                                                                      v bounded queue, DLQ
        Dashboard <- app (proxy) <- Reporting service <- ClickHouse (rollups) <- Kafka ad-events <- KafkaEventSink
                                                              ^
                              Reconciler: booked spend (ledger) vs billed spend (ClickHouse) -> drift metric, credit
```

[docs/architecture.md](docs/architecture.md) has the data flow, latency budget and failure-mode table; [docs/adr/](docs/adr/) the decisions;
[docs/interview-notes.md](docs/interview-notes.md) interview preparation grounded in this code.

## Benchmarks

Method: open-loop generator, latency measured from the intended send instant (no coordinated omission), 15 s warm-up excluded, seeded
payloads. **The load generator runs on the same machine as the server** and uses about 1.4 cores, so these are laptop numbers with the
generator as a co-tenant. They were taken on the lite edition's hot path; the full edition adds Kafka-bound work off the request thread
but was not separately load-tested.

| Run | Hardware | Rate | Requests | p50 | p95 | p99 | p99.9 | max | Errors | Overspend |
|---|---|---|---|---|---|---|---|---|---|---|
| Baseline, steady | Windows 11, 12 logical cores, 16.9 GB, Java 21.0.12, 1 GB heap, G1, virtual threads | 5,000/s | 225,000 | 0.17 ms | 0.46 ms | 0.91 ms | 7.27 ms | 24.1 ms | 0.00% | 0.0% |

Raw output: `bench/results/baseline-steady-5000/`. Budgets were scaled up for the run so campaigns did not run dry (no-bid 0%). Not run:
10,000-20,000 req/s (needs a larger machine), the 1 h soak, and a clean k6 comparison. Config experiments, the GC comparison and a
profile-driven set of fixes are in [docs/optimization.md](docs/optimization.md); the profile-driven fixes were measured in an interleaved A/B on a quiet machine: they **halve GC work** (24 to 13 collections per run, all 8 runs agree) but the
latency tail difference is within run-to-run noise at this sample size, so no latency gain is claimed (section 4 of that doc; `bench/results/ab/`).

## Chaos results

`make chaos` drives 3,000 req/s for 150 s and injects faults in-process (Redis down 30 s, then +200 ms on the shared stores; [ADR 0007](docs/adr/0007-fault-injection.md),
this is not Toxiproxy network chaos). The run uses $0.20 leases with a 20 s TTL so budget effects show within 2.5 minutes.
Raw series and plot: `bench/results/chaos/`, `chaos-before-breaker/`, `chaos-with-breaker-single-scheduler/`.

| Phase | Behaviour | Measured |
|---|---|---|
| Steady | baseline | server p99 about 0.1 ms, no-bid 38.7% (small leases) |
| Redis down | engines spend the remaining local lease, then fail closed; frequency cap fails open and flags `degraded` | no-bid rises to 80.5%, latency unchanged (p99 0.08 ms), 0 errors; recovers to 40.6% |
| +200 ms on shared stores | frequency-cap lookup is synchronous on the hot path | see below |

| +200 ms, whole-run figures from the generator | p99 | errors |
|---|---|---|
| Before the circuit breaker | 22,968 ms | 11.5% |
| After (Resilience4j breaker, slow call > 10 ms counts as failure, open 2 s) | 1.6 ms | 0.00% |

The per-second server average in the fault window is distorted by a stalling stats sampler, so the generator-side figures are the reliable ones.

## Learning loop

Impressions and accepted clicks go to the event log; `ml/train.py` trains on a time-ordered 80/20 split and exports sparse weights
atomically; the engine polls the file and swaps weights without a restart. On 131k impressions: holdout log loss 0.1120 against 0.1145
for a constant prediction, AUC 0.629; the mean gap between predicted and hidden true CTR fell from 0.0042 to 0.0008 after the reload.
Evidence in `bench/results/learning/`. The first attempt had AUC 0.50 (unlearnable ground truth and campaign-crossed features); the fix is in the interview notes.

## What was verified

- `make test`: 56 tests, green on a full run across all modules: unit, jqwik property and Testcontainers tests, including **Postgres + Kafka** (atomic outbox rollback, tombstones, replay from
  the compacted topic), a **ClickHouse** rollup test running the real DDL, Redis Lua scripts, the Kafka sink (delivery, DLQ, shedding, dead broker),
  reconciliation, and Java/Python hashing parity.
- **Full stack as containers (`deploy/compose.full.yml`):** app, reporting, Kafka, Postgres, ClickHouse, Redis, Jaeger, Prometheus and Grafana all ran healthy.
  With the app inside its container, after traffic stopped and the pipeline drained, the app's billing ledger and ClickHouse's billed spend since the app started were
  **identical: 31,132,473 micros over 9,802 impressions**, with 0 events dropped, lost or dead-lettered. (An earlier run with the app on the host against containerized
  infrastructure also reconciled; the only difference there was two events I injected by hand.)
- **One distributed trace across two services** in Jaeger: the app's request span (216 ms), its outgoing call (188 ms) and the reporting service's span (162 ms) share a trace id.
- **Exact reconciliation, checked against ground truth** (`bench/reconcile-check.sh`, 60 s token lifetime, 300 req/s, real Kafka + ClickHouse + Redis):
  the credit tracks the never-billable spend while traffic is still running (1.8M micros after 2 min, 16.2M after 6 min; the previous rule credited 0), and after
  traffic stopped and tokens expired, **credited = booked - billed = 20,890,255 micros, difference 0**, with no campaign billed above its booked amount.
  About 11-12% of booked spend is never billed (impressions never viewed, bot traffic). A first version of my check reported a 1.0M gap; it was my script comparing global
  totals, and the gap was exactly the billing of 172 impressions on seed campaigns that were removed at startup (ClickHouse shows 1,016,384 micros under them).
- Reporting API: an advertiser sees only their own rows (Nike 1,240 of 13,857 impressions in one check), flags are admin-only, and injection attempts return 400.
- The lite image builds and ran under a 512 MB limit at about 184 MiB.
- Kubernetes manifests render with kustomize (19 resources). **They were not applied to a live cluster** (no cluster was available), so the HPA demo is untested.

## Known limitations and what is not built

- Reconciliation reads booked spend from one engine's in-memory counter (resets on restart; one engine only), not yet from the Redis ledger.
- Protobuf and a schema registry (JSON is used), a WebFlux gateway, Toxiproxy network chaos, exactly-once delivery
  to ClickHouse, the stretch items (replay, bid shading). See [ADR 0006](docs/adr/0006-lite-mode-and-scope.md).
- Three profile-driven fixes were made; they halve GC work but their latency effect is not statistically established (see above).
- With $5 leases a popular campaign can drain a lease faster than the 500 ms renewal tick at 5,000 req/s (377,633 denied spends in the baseline); no-bid stayed 0%,
  but that campaign loses fill. Size chunks from observed spend rate, or renew faster.
- Synthetic traffic: the learned model and the pricing comparison describe the simulator, not a market.
- No real deployment was performed (Render config exists for lite; full needs a VM or cluster).

## Deploy

**Step-by-step guide: [docs/HOSTING.md](docs/HOSTING.md).** **Lite, free:** `render.yaml` deploys the single image on Render's free plan (512 MB, sleeps when idle; the simulator drives itself so the
dashboard is live). Koyeb and Fly.io work with the same `Dockerfile`. **Full:** needs roughly 4 cores and 6-8 GB; run `make up-full` on a VM
(an Oracle Cloud Always Free ARM VM has fit this, subject to its current terms and availability) or apply the Kubernetes manifests.
