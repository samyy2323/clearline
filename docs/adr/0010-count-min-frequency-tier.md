# ADR 0010: Optional count-min tier in front of the frequency-cap store

Status: accepted (optional, off by default; `FREQ_LOCAL_TIER=true`, on in the full compose stack)

## Context
The frequency-cap lookup is the only remote call on the auction path and every candidate needs one. Most users are nowhere near their cap, so
most of those lookups can only answer "no".

## Decision
`TieredFrequencyCap` keeps a windowed count-min sketch (ring of time buckets, `depth x width` counters each) of impressions recorded through this
instance. Count-min can only OVER-count, so `estimate < cap` proves the user is under the cap as far as this instance has seen, and the candidate is
answered locally. Only candidates whose estimate has reached the cap (truly capped, or a hash collision) go to Redis in one batched call, and Redis's
answer is authoritative. Writes always go to Redis, so it stays complete. It sits in front of the Resilience4j-guarded store, so a slow Redis still trips the breaker.

## Measured (`TieredFrequencyCapTest.measuredAccuracyAndRemoteLoadReduction`: 400k requests, 8 candidates each, cap 3, Zipf-like users, depth 4)

| sketch width | memory (24 hourly buckets) | share of cap checks still sent to Redis | wrong answers |
|---|---|---|---|
| 2^14 | 6 MiB | 58.1% | 0 |
| 2^16 | 24 MiB | 15.1% | 0 |
| 2^18 | 96 MiB | 14.1% | 0 |

About 14% of checks are genuinely capped and must go to Redis whatever the sketch does, so 2^16 is within about one point of the floor; 2^14 collides too much to help.
Collisions cost an extra lookup, never a wrong answer. A property test (300 runs) checks the sketch never under-counts.

## Consequences
Memory is fixed by the sketch size, not by the number of users. The caveat is across engines: a sketch only knows this instance's impressions, so with N engines a cap can be
overshot by up to about N-1 impressions per user unless users are routed to the same engine (hash on user id). One instance is exact; the limit is documented rather than hidden.
