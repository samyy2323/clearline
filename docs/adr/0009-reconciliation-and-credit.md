# ADR 0009: Reconcile booked spend with billed spend; credit back only what can no longer be billed

Status: accepted (single-engine semantics)

## Context
The engine books a campaign's spend when it AWARDS an impression, which is what keeps overspend bounded. Billing happens later, when a
signed impression event arrives. Wins that are never rendered stay booked but are never billed, so the budget ledger drifts away from
confirmed spend and campaigns under-deliver. The spec asks for a job that compares confirmed spend with the Redis counters, corrects
drift and emits a drift metric.

## Decision
`Reconciler` runs every minute. Source of confirmed spend is a `ConfirmedSpendSource`: the in-memory ledger in lite mode, a ClickHouse
query (`type='imp' AND flag=''`) in full mode. It always publishes `budget.reconciliation.drift.micros` (booked minus billed, which includes
impressions still in flight).

Correction is deliberately conservative. Tracking tokens expire after 30 minutes, so a booking older than that can never be billed. Let
`B(T)` be spend booked up to `T = now - tokenTtl`, and `billed` the spend billed so far (it also includes newer bills, so it can only
over-state what the old bookings were billed). Then `max(0, B(T) - billed)` is a lower bound on spend that is booked, expired and
never billed. Whatever part of that was not credited earlier is returned via `LeaseStore.credit` (a Lua script in Redis), which never
lowers `committed` below what open leases still hold. If the confirmed-spend source is unavailable the run is skipped and nothing is credited.

## Alternatives
Crediting the instantaneous drift: would return budget for impressions that are merely late, then overspend when they arrive.
Per-impression "reserve then confirm" accounting: exact but needs a durable pending-reservation store on the hot path.

## Consequences
Under-delivery from never-rendered wins is corrected with a 30-minute lag, not instantly. With several engines each one needs its own
booked figure; the code and tests assume one. Covered by `ReconcilerTest` (no credit inside the token lifetime, credit once and
idempotently afterwards, none when billing catches up, skip on an unavailable source).
