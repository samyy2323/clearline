# ADR 0009: Reconcile booked spend with billed spend; credit back exactly what can no longer be billed

Status: accepted (single-engine semantics). Supersedes the first, conservative version of this ADR (see "History").

## Context
The engine books a campaign's spend when it AWARDS an impression, which is what keeps overspend bounded. Billing happens later, when a
signed impression event arrives. Wins that are never rendered, bot traffic, and responses that missed their deadline stay booked but are
never billed, so the budget ledger drifts away from confirmed spend and campaigns under-deliver. The spec asks for a job that compares confirmed
spend with the Redis counters, corrects drift and emits a drift metric.

## Decision
Every tracking event carries `booked_ms`: the time its spend was booked (token expiry minus the token lifetime). ClickHouse (and the lite
in-memory ledger) can therefore total billed spend **by booking time**, not billing time.

`Reconciler` runs every minute and keeps a snapshot of each campaign's booked spend. A booking made at time `P` can no longer be billed once
`P + tokenTtl` has passed, because its tracking tokens have expired. So for the snapshot `P` taken at least one token lifetime ago:

```
credit = booked(P) - billedForBookingsUpTo(P + 2 s) - alreadyCredited
```

is exactly the spend booked up to `P` that will never be billed. It is returned to the pool through `LeaseStore.credit` (a Lua script in Redis) which
never drops `committed` below what open leases still hold. The 2 s margin covers the milliseconds between booking a spend and stamping its ticket; bills
for bookings inside the margin can only lower the credit, never raise it, so the margin errs on the safe side. The token lifetime is one setting
(`exchange.token-ttl-ms`) shared by the signer and the reconciler, and the reconciler's lag is that value: lowering the lag below it would make credits
premature.

Separately, `budget.reconciliation.drift.micros` publishes the unconditional gap `booked - billed` (it includes impressions still in flight),
`...unbillable.micros` the exact never-billable amount, and `...credited.micros` what has been returned.

If the confirmed-spend source is unavailable the run is skipped and nothing is credited.

## History: why the first version was replaced
The first version had no booking time, so it subtracted ALL bills so far from the old booked figure. That is safe (bills for newer bookings only
make the credit smaller) but in steady traffic it credited **nothing**: measured on a clean run against real ClickHouse, drift grew 3.3M -> 16.8M micros over
274 s (about 11-12% of booked spend) while credited stayed 0. It only fired once billing stalled.

## Alternatives
Crediting the instantaneous drift: returns budget for impressions that are merely late, then overspends when they arrive.
Per-impression "reserve then confirm" accounting: exact but needs a durable pending-reservation store on the hot path.

## Consequences
Under-delivery from never-rendered wins is corrected with a one-token-lifetime lag (30 minutes by default). Booked spend is read from this engine's
in-memory lease manager, so it resets on restart while ClickHouse totals persist (drift under-reports right after a restart), and with several engines each
needs its own booked figure; reading booked spend from the Redis ledger would fix both. The lite ledger keeps per-second booking buckets and folds
anything older than about 4,000 s into one bucket, so the token lifetime must stay under roughly an hour.

Covered by `ReconcilerTest` (exact credit while newer bookings keep being billed, nothing inside the token lifetime, idempotent, nothing when fully
billed, skip and recover when the source is down), `ClickHouseRollupIT` (cohort query splits by booking time, not billing time) and the live ground-truth
run in `bench/reconcile-check.sh` (results in `bench/results/reconcile/`).
