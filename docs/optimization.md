# Optimization and measurement notes

What was measured, what changed the numbers, and what did not. Every figure comes from a saved run in `bench/results/`.
Hardware for all runs: Windows 11, 12 logical cores, 16.9 GB RAM, Java 21.0.12, 1 GB heap unless stated; the load generator
runs on the same machine (about 1.5 cores of CPU), so differences smaller than run-to-run noise mean nothing.

**Partly done:** JFR profiling with flame graphs (`bench/results/profile/*/flamegraph.svg`) and allocation-reduction work are done and measured in section 4; primitive-collection and
pre-sized-buffer work in the engine itself was not done. The spec asks for these; they were not run, so there are no flame graph screenshots and no allocation
claims. The hot path does avoid per-campaign hashing and ORM-style work by construction (bitset index, flat weight array),
but that is a design property, not a measured optimization.

## 1. Circuit breaker on the frequency-cap call (large effect, under fault)

Problem found by the chaos run: the frequency-cap lookup is the only remote call on the auction path and was synchronous, so
200 ms of Redis latency became 200 ms of latency for every auction.

| | Generator p99 over the 150 s run | Generator errors |
|---|---|---|
| Before (`bench/results/chaos-before-breaker/`) | 22,968 ms | 11.5% |
| After (`bench/results/chaos/`) | 1.6 ms | 0.00% |

Change: `ResilientFrequencyCap` wraps the store in a Resilience4j circuit breaker that counts errors and calls slower than 10 ms;
when open, lookups are skipped for 2 s (fail open, `degraded` flag) and writes are dropped instead of queued onto a sick store.
Unit-tested (`FrequencyCapTest.slowStoreOpensTheBreaker...`). The first second of the fault still sees the slow call.

## 2. GC choice (tail latency, single runs)

Steady 5,000 req/s, 30 s measured after 15 s warm-up, one JVM per row (`bench/experiments.sh`):

| Configuration | p50 | p95 | p99 | p99.9 | max |
|---|---|---|---|---|---|
| G1, virtual threads (baseline, 45 s run) | 0.173 ms | 0.461 ms | 0.914 ms | 7.27 ms | 24.1 ms |
| G1, virtual threads (repeat) | 0.357 ms | 0.453 ms | 0.577 ms | 7.39 ms | 11.7 ms |
| G1, platform threads | 0.362 ms | 0.452 ms | 0.551 ms | 6.98 ms | 12.0 ms |
| ParallelGC | 0.252 ms | 0.409 ms | 0.513 ms | 1.48 ms | 6.7 ms |
| ZGC (generational) | 0.243 ms | 0.427 ms | 0.528 ms | 0.94 ms | 5.0 ms |

Reading it: p50 through p99 are inside the noise (the two identical baselines differ by 2x at p50). The p99.9 tail is the
only difference that repeats: about 7 ms on all three G1 runs versus 1.5 ms (Parallel) and 0.94 ms (ZGC). That is consistent with
G1 pauses, but GC logs were not collected, so the cause is inferred, not shown. Each non-G1 configuration is a single run.
Recommendation for a larger machine: ZGC. The free-tier container keeps the default collector because ZGC needs more memory
headroom than 512 MB allows.

## 3. Rate feed-forward in the pacing controller (correctness, not latency)

A cumulative-error PI controller let a campaign spend 43% of its budget against an 8% target in the first 40 s of a live run,
because that error stays tiny until too late. Adding a spend-rate feed-forward term gave 9.5% actual against a 9.4% target in
the same setup; `PacingControllerTest.extremeDemandDoesNotBlowThroughTheCurve` drives 40x demand through it.

## 4. The profile-driven fixes, measured on a quiet machine (A/B)

The profile (`bench/results/profile/`) showed about 49 KB allocated per request, 28% of it Jackson buffers that virtual threads cannot recycle and 19% of it a new
HMAC object per signature. Three fixes were made: a pooled HMAC with one payload encode, Jackson's lock-free recycler pool, and parsing the bid body by hand.

To isolate them, `bench/ab-build.sh` builds variant **A** (current code with ONLY those three changes reverted) and **B** (current code), and `bench/ab.sh` runs
A B A B ... at 5,000 req/s, 30 s measured after 15 s warm-up, one fresh JVM per run, logging the machine's CPU before each run (`bench/results/ab/`). The machine was
quiet (the earlier contaminated runs were caused by an unrelated process using half the CPU). Pair 4 was disturbed mid-run for both variants and is excluded, as is a run that
started at 27% machine CPU.

| Quiet runs, median [min .. max] | A (without the fixes, n=2) | B (with the fixes, n=3) |
|---|---|---|
| p50 | 0.233 ms [0.222 .. 0.244] | 0.233 ms [0.222 .. 0.261] |
| p95 | 0.678 ms [0.609 .. 0.748] | 0.606 ms [0.600 .. 0.687] |
| p99 | 4.396 ms [0.849 .. 7.942] | 0.814 ms [0.810 .. 1.002] |
| p99.9 | 28.6 ms [8.4 .. 48.9] | 7.7 ms [7.0 .. 8.6] |
| GC collections per run | 24 | 13 |
| GC pause total per run | 246 ms | 133 ms |

What this supports and what it does not:
- **Supported, consistent in all 8 runs (including the excluded ones):** the fixes roughly **halve GC work** (A: 23-25 collections per run, B: 13-14; total pause time about 246 ms to 133 ms).
- **Not supported:** a latency claim. p50 is identical and p95 is about 11% better, but the ranges overlap. B's tail was steady (p99 0.81-1.00 ms); A had one quiet run with a spike (p99 7.9 ms, p99.9 49 ms) and one without, so with n=2 vs n=3 the headline "-81% p99" comes from a single run and should not be quoted.
- A larger sample (10+ quiet pairs) would be needed to turn the tail difference into a result. The mechanism (half the garbage, so fewer and shorter pauses) is plausible and consistent with the GC data, but it is not demonstrated end to end.

## Things that did not matter

- **Virtual threads vs platform threads** (ADR 0005): no measurable difference at 5,000 req/s on this workload (table above).
  A WebFlux gateway was not built, so nothing is claimed about it.

## Measurement fixes that changed what I believed

- The first generator (JDK `HttpClient`) reported ~13 ms p50 against a server reporting 0.1 ms and used two cores at 2,000 req/s.
- The replacement socket client still reported ~9 ms because `LockSupport.parkNanos(50 us)` sleeps in 1-15 ms quanta on Windows;
  replacing the waits with blocking queue hand-off and a spinning dispatcher gave p50 0.24 ms at 2,000 req/s. Latency is now split into
  client queue wait and service time in every result to make this kind of artifact visible.
- The first chaos run showed one blocked scheduled task starving the stats sampler (single-thread scheduler); the pool is now 4.

## Known capacity issue found by the baseline

At 5,000 req/s with $5 leases the server counted 377,633 denied spends: a popular campaign drains a lease faster than the 500 ms
renewal tick. Auctions fall through to the next candidate, so no-bid stayed 0%, but that campaign loses fill. Not fixed; the fix
is to size chunks from observed spend rate or renew faster.
