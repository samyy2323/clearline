# Clearline

A real-time ad exchange. Advertisers manage campaigns in a dashboard; each ad request is scored, priced and awarded in memory
in well under the 50 ms budget; campaign budgets are spent from local leases of an atomic Redis ledger so overspend is bounded
and measured instead of hoped for. Clearing prices come from first-price or generalized second-price auctions that run side by
side on live traffic, tracking events are signed and de-duplicated so an impression is billed at most once, and a click model
is trained offline and hot-reloaded into the running engine.

> This is a portfolio project. Traffic is synthetic, brand names in the demo data are labels only (no logos, no real
> performance data), and every number below comes from a run whose raw output is saved in `bench/results/`.

## Contents
- [Quick start](#quick-start) · [What is built](#what-is-built) · [Architecture](#architecture) · [Benchmarks](#benchmarks)
- [Chaos results](#chaos-results) · [Learning loop](#learning-loop) · [Known limitations](#known-limitations-and-what-is-not-built) · [Deploy for free](#deploy-for-free)

## Quick start

```bash
make demo        # builds, starts the app on :8080, opens the dashboard, runs the compressed day (24 h in 20 min)
make up          # full stack in Docker: app + Redis + Prometheus (:9090) + Grafana (:3000)
make up-lite     # one small container, in-memory stores (laptop profile)
make test        # unit + jqwik property + Testcontainers tests (needs Docker)
make bench       # open-loop benchmark, results in bench/results/
make chaos       # fault injection under load, before/during/after series and plot
make train       # offline pCTR training from the impression/click log
```

Needs Java 21, Maven, Node 22, Docker, Python 3.10+ (training only). On Windows use Git Bash. Development logins:
`admin` / `admin123`, and `advertiser1` to `advertiser8` / `advertiser123` (Nike, Samsung, Spotify, Zomato, HDFC Bank,
Hyundai, Booking.com, Nykaa). Set `ADMIN_PASSWORD`, `ADVERTISER_PASSWORD`, `JWT_SECRET`, `HMAC_SECRET` for anything real.

## What is built

- **Auction.** OpenRTB-lite requests; in-memory inverted index (geo, device, segment, category, daypart) updated from campaign
  change events; eCPM = bid x pCTR with a hashed logistic regression scored in process; floors, size matching, one winner per
  slot; first-price and GSP selectable or A/B-split deterministically with revenue recorded per arm; per-stage deadlines that turn
  a slow stage into a no-bid, never a late bid.
- **Budget control.** Lease-based spend (`libs/budget`): atomic Lua scripts, local lock-free spend, async renewal and settle,
  graceful release, expiry reclaim with late-spend tombstones. Invariant tested with jqwik. PID-style pacing with an injectable clock
  (compressed day for demos).
- **Frequency caps.** Per-user per-campaign sliding window of time buckets; Redis version does a whole candidate batch in one Lua
  call. Fails open and flags `degraded`; guarded by a Resilience4j circuit breaker that also trips on slow calls.
- **Events.** HMAC-signed, expiring win/impression/click URLs (event type is inside the MAC); de-duplication; fraud checks (bot user
  agents, click without impression, per-user and per-IP click floods) that flag and count instead of silently dropping.
- **Security.** JWT roles (admin, advertiser) with tenant isolation, input validation, per-IP rate limit and load shedding at the
  gateway, secrets from environment.
- **Learning loop.** Offline Python trainer (log loss, AUC, calibration), atomic weight export, hot reload with no restart; a Java
  test proves training and serving hash identically.
- **Dashboard.** React + TypeScript + TanStack Query + ECharts: performance and pacing, campaign CRUD, pricing experiment, system
  health (the proof screen), simulator control with failure injection. Splash, sidebar console, bundled fonts, no external requests.
- **Observability.** Micrometer metrics, Prometheus scrape, provisioned Grafana dashboard (latency by stage, throughput, overspend,
  denied spends, degraded lookups).
- **Simulator.** Open-loop HTTP load generator (HdrHistogram, seeded, measures from intended send time, reports its own CPU) and an
  in-process behavior simulator (Zipf-like users, correlated geo/device/segments, hidden true CTR, duplicate clicks, click floods,
  bots). A k6 script (`bench/k6/bid.js`) provides an independent cross-check.

## Architecture

See [docs/architecture.md](docs/architecture.md) for the data flow, hot vs async path, latency budget and failure-mode table, and
[docs/adr/](docs/adr/) for the decisions (leases vs per-bid decrement, first vs second price, in-memory index, outbox, virtual
threads, lite mode and scope, fault injection). Interview preparation grounded in this code: [docs/interview-notes.md](docs/interview-notes.md).

## Benchmarks

Method: open-loop generator, latency measured from the intended send instant (no coordinated omission), 15 s warm-up excluded,
seeded payloads. **The load generator runs on the same machine as the server** and uses about 1.4 cores while spinning its
dispatcher, so these are laptop numbers with the generator as a co-tenant, not a clean-room result.

| Run | Hardware | Rate | Requests | p50 | p95 | p99 | p99.9 | max | Errors | No-bid | Overspend |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Baseline, steady | Windows 11, 12 logical cores, 16.9 GB, Java 21.0.12, 1 GB heap, G1, virtual threads | 5,000/s | 225,000 | 0.17 ms | 0.46 ms | 0.91 ms | 7.27 ms | 24.1 ms | 0.00% | 0.0%\* | 0.0% |

\* Budgets were scaled up for the benchmark so campaigns do not run dry while measuring the auction path, so no-bid is 0%. Raw
output: `bench/results/baseline-steady-5000/` (`loadgen.json`, `server-snapshot.json`, `prometheus.txt`, `env.txt`).
Load generator CPU: 142% of one core on average. Not run: 10,000 to 20,000 req/s (needs a larger machine), the `soak` (1 h),
`ramp` and `spike` presets over HTTP, and a clean k6 comparison (the k6 run through Docker Desktop on Windows measured a 1.8 ms median
but heavy tails dominated by the container network path, so it is not used as a headline number).

A note on the first generator I wrote: it measured ~13 ms p50 against a server reporting 0.1 ms. The cause was my client (see
the interview notes), which is why the generator reports its own CPU and splits client queue wait from service time.

## Chaos results

`make chaos` drives 3,000 req/s of open-loop load for 150 s and injects faults in-process (Redis down 30 s, then +200 ms on the
shared stores for 30 s; see [ADR 0007](docs/adr/0007-fault-injection.md), this is not Toxiproxy network chaos). To make budget effects visible within
2.5 minutes the run uses $0.20 leases with a 20 s TTL, so its steady-state no-bid rate (about 39%) is higher than the benchmark's.
Raw series, events and plot: `bench/results/chaos/` (final), `chaos-before-breaker/`, `chaos-with-breaker-single-scheduler/`.

| Phase | What happens | Measured |
|---|---|---|
| Steady | baseline | server p99 about 0.1 ms, no-bid 38.7% |
| Redis down | engines spend the remaining local lease, then fail closed (no budget, no bid); frequency cap fails open and flags `degraded` | no-bid rises to 80.5%, **latency unchanged (p99 0.08 ms), 0 errors**; recovers to 40.6% after Redis returns |
| +200 ms on shared stores | frequency-cap lookup is synchronous on the hot path | see below |

**+200 ms, before and after adding a circuit breaker on the frequency-cap call** (same scenario, whole-run figures from the generator):

| | Generator p99 over the 150 s run | Generator errors | Notes |
|---|---|---|---|
| Before | 22,968 ms | 11.5% | every auction paid the 200 ms call; queueing explodes (open-loop accounting counts it) |
| After (Resilience4j breaker: errors and calls slower than 10 ms count as failures; open for 2 s) | 1.6 ms | 0.00% | first ~1 s still sees 202 ms, then the breaker opens and p99 returns to 0.06 ms for the rest of the window |

Caveats: the per-second average req/s in the fault window (846) is distorted by the stats sampler stalling and catching up while
the injected latency blocks other work, so the generator-side 3,000 req/s with 0 errors is the reliable throughput figure. The
first run of this scenario also exposed a single-thread scheduler that let one blocked task starve the stats sampler; the pool is now 4.

## Learning loop

Impressions and accepted clicks are written to `data/events.jsonl`; `ml/train.py` trains on a time-ordered 80/20 split and exports
sparse weights atomically; the engine polls the file and swaps weights without a restart. Run on 131k impressions: holdout log loss
0.1120 against 0.1145 for a constant prediction, AUC 0.629. After the reload the mean gap between predicted and hidden true CTR
fell from 0.0042 (first five 5-second windows) to 0.0008 (last five). Evidence: `bench/results/learning/`
(`ctr_convergence.png`, `calibration.png`, `metrics.json`, `ctr_windows.csv`). The first training attempt had AUC 0.50: the hidden
CTR was an unlearnable hash per segment x category cell and the features were all crossed with the campaign; the fix and why
are in the interview notes.

## Known limitations and what is not built

- **Not built:** Kafka and Schema Registry, ClickHouse rollups and a separate reporting service, Postgres + Flyway, Protobuf,
  OpenTelemetry tracing across services (there is one process), Helm/kind, a WebFlux gateway, Toxiproxy network-level chaos, a
  Bloom/count-min tier, the stretch items. See [ADR 0006](docs/adr/0006-lite-mode-and-scope.md). Lite mode keeps campaigns and
  aggregates in memory, so state resets on restart.
- **Lease sizing:** with $5 leases a popular campaign can drain a lease faster than the 500 ms renewal tick at 5,000 req/s (the
  baseline run shows 377,633 denied spends); auctions fall through to the next candidate so no-bid stayed 0%, but that campaign
  loses fill. Size the chunk from observed spend rate, or renew faster.
- **Pacing:** tuned on a linear target curve; traffic-shaped curves are supported by the controller but not exercised in a test.
- **Synthetic traffic:** the learned model and the pricing comparison describe the simulator, not a real market.
- **Optimization write-up:** see [docs/optimization.md](docs/optimization.md) for what was and was not measured.

## Deploy for free

`render.yaml` deploys the single image on Render's free plan (512 MB, sleeps when idle; the simulator drives itself so the dashboard
is live on arrival). Koyeb and Fly.io work with the same `Dockerfile`. For the full Redis-backed stack run `make up` locally or on
a free-tier VM. Free hosts have ephemeral disks, so lite mode re-seeds demo campaigns on every start.
