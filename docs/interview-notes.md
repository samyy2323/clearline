# Interview notes

Answers are grounded in this repository. Where something is not built, the answer says so.

## How much can a campaign overspend, and how do you know?
Bound: `budget + lease_size x (instances + crashes)`. Without a crash the slack is only spend since an instance's last settle
heartbeat (at most one lease per instance). A crash can lose one lease worth of unreported spend, which the store then re-leases.
I know because `OverspendInvariantProperties` (jqwik, 800 randomized runs of up to 400 steps each) interleaves spends on three
instances, clock advances, hard crashes and graceful shutdowns and asserts the bound after every step; the Lua scripts are
checked against a real Redis in `RedisLeaseStoreIT`. Measured overspend in the load runs is reported by the app as
`overspendPct` (true local spend above budget), shown on the System health page and exported as `budget.overspend.pct`.

## What happens to a crashed instance's lease?
It expires after `LEASE_TTL_MS`. Reclaim returns `granted - lastReportedSpend` to the pool. The amount spent after the last
heartbeat is the unavoidable loss; it is bounded by one lease and is why chunk size trades stranded budget against renewal
frequency. If the "crashed" instance was only partitioned and later reports, a tombstone charges its extra spend back so the
ledger converges. The engine also stops spending `max(500 ms, ttl/10)` before the store may reclaim, so clock skew does not
create a double-spend window.

## How do you avoid double billing?
Each impression has an event id (`requestId|slot|campaign`) inside an HMAC-signed token. `BillingLedger.billImpression` uses an
atomic add-if-absent on a two-generation dedupe set, so concurrent duplicates race to a single winner. The simulator injects
duplicate impressions and clicks; `billing.driftMicros` (expected minus actual) stays at 0. Limit: the dedupe window is bounded
(about 2 million ids per generation), so a replay older than that would be accepted; the token expiry (30 min) closes most of that gap.

## What are the sources of p99 latency?
Measured, not guessed: at 5,000 req/s on a laptop the p99 is under a millisecond and p99.9 is a few milliseconds, so the tail
is GC and scheduler noise rather than the algorithm. The things that would move it in production: the frequency-cap network
call (the only remote call, so it is the first thing a slow Redis hurts, and the chaos run shows it), G1 pauses with a large
heap, candidate count (scoring is linear in candidates after retrieval), and `exp` per candidate. Stage timers separate these.

## How would you scale this 10x?
Engines are stateless apart from leases, so add instances; overspend slack scales with instance count, so shrink the lease for
small campaigns or shard campaigns across instances. The index rebuild is O(campaigns) per change, so move to copy-on-write
posting lists. Replace the in-memory outbox with Postgres + Kafka (the interfaces are in place). Put the frequency cap behind a
local Bloom or count-min tier to cut Redis load (documented, not built). Move aggregates to ClickHouse (not built).

## Why leases instead of a Redis counter per bid?
ADR 0001: per-bid decrements put Redis p99 into every auction and make it a hard dependency. Leases make Redis outage a
graceful degradation, at the cost of a bounded, stated overspend.

## Why first-price and GSP both?
ADR 0002: the market moved to first-price; GSP is incentive compatible. A deterministic A/B split lets both run on the same
traffic. Caveat: with non-strategic synthetic bidders the revenue comparison is not an equilibrium result.

## How does the pacing controller work, and what did you get wrong first?
Feed-forward on the spend rate (remaining budget / remaining time, divided by the estimated spend rate at full participation)
plus a PI trim on the error to the target curve. First version was PI on cumulative spend only: in a live run a campaign burned
43% of its budget against an 8% target because that error is tiny until it is too late. Rate feed-forward fixed it
(9.5% actual vs 9.4% target in the same setup), and a regression test drives 40x demand through the controller.

## What did the load generator get wrong, and how did you find out?
Two things. A JDK `HttpClient` generator used about two cores at 2k req/s and added ~13 ms of client latency, while the server
reported 0.1 ms. Then my own socket client still showed ~9 ms because two worker/dispatcher waits used `parkNanos`, which sleeps
in 1-15 ms quanta on Windows. I found it by decomposing latency into client queue wait and service time, replaced the waits, and the
same 2,000 req/s run dropped to p50 0.24 ms. The generator is open-loop and measures from the intended send time, and it reports
its own CPU so it is never a hidden bottleneck.

## Why Kafka between the app and ClickHouse, and what if Kafka is down?
ADR 0008. The request path only offers an event to a bounded queue; a sender thread owns the producer with `max.block.ms=200`. If Kafka is slow or down the queue fills
and events are dropped and counted (shedding), so auctions are unaffected. Failed deliveries go to a dead-letter topic. Billing is protected earlier: the event-id
dedupe and the ledger live in the app, and ClickHouse is a reporting store, not the ledger. In a run with Kafka healthy the ledger and ClickHouse matched to the micro.

## What does the transactional outbox buy you?
A campaign write and its change event are one Postgres transaction, so the event can't be lost or announced without the write. `PgOutboxKafkaIT` proves a failed write leaves
neither a row nor an event, that a delete travels as a Kafka tombstone, and that a brand-new engine rebuilds the identical index from the compacted topic without calling Postgres.
Delivery is at-least-once, so the index apply is idempotent.

## Does your reconciliation actually fix drift?
Honest answer: it measures it and credits only what is provably unbillable, and in steady traffic that credit stays at zero (ADR 0009 has the numbers: 11-12% drift,
0 credited). The conservative rule is safe and nearly inert; the exact fix is to key billed spend by booking time. I'd rather say that than claim it works.

## Why ClickHouse and AggregatingMergeTree?
Time-bucketed aggregates over a high-volume append-only stream. Materialized views write `sumState` partials per minute, queries use `sumMerge`, so results are right whether or not
background merges have run. A test inserts two batches in the same minute and checks they combine rather than overwrite.

## What is not built?
Kafka, ClickHouse, Postgres, Protobuf, cross-service tracing, Helm, WebFlux, Toxiproxy, Bloom tier, stretch items. See ADR 0006.
