# ADR 0001: Budget control with leases, not a per-bid Redis decrement

Status: accepted

## Context
Every auction that wins must debit a campaign's budget. A shared counter in Redis gives exact accounting, but a network
round trip per bid puts Redis on the hot path (p99 of every auction becomes Redis p99), makes Redis a hard dependency for
bidding, and caps throughput at what one hot key can sustain.

## Decision
`budget-pacing` hands each engine instance a lease (default $5, `LEASE_CHUNK_MICROS`) of a campaign's budget through one
atomic Lua script (`libs/budget/src/main/resources/lua`). The engine spends from the lease with a lock-free CAS
(`LeaseManager.trySpend`), renews asynchronously below 40% remaining, settles cumulative spend as a heartbeat, and returns
the unused part on graceful shutdown. A lease carries a TTL; the engine stops spending `max(500 ms, ttl/10)` before the
store may reclaim it.

Accounting: `committed = settled spend of closed leases + granted amount of open leases`. An expired lease returns
`granted - lastReportedSpend` to the pool. A late `release` for an already reclaimed lease charges the difference back
(tombstones), so the ledger converges once the engine reports.

## Invariant
True spend never exceeds `budget + lease_size x (instances + crashes)`. With prompt settles and no crash the only slack is
the spend since the last heartbeat (at most one lease per instance). A hard crash can lose one lease of unreported spend,
which is then re-leased. Verified by jqwik properties with randomized spends, time advances, crashes and graceful shutdowns
(`OverspendInvariantProperties`), by a multi-threaded test, and by Lua scripts against a real Redis (`RedisLeaseStoreIT`).

## Alternatives
- Per-bid `DECRBY`: exact, but latency and availability coupling described above.
- Probabilistic or periodic sync of local counters: no hard bound.

## Consequences
Unspent lease on an idle instance is stranded until release or expiry: at most `lease_size x instances` per campaign at any
moment, which is why small campaigns need small chunks. When Redis is down the engine keeps spending its local lease, then
fails closed (no budget, no bid). Overspend is bounded and measured, not zero.
