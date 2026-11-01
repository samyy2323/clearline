# ADR 0002: Pluggable first-price and GSP pricing with a deterministic A/B split

Status: accepted

## Context
The industry moved from second-price to first-price auctions. Both are worth being able to run and compare, on the same
traffic, without redeploying.

## Decision
`PricingStrategy` has two implementations. First-price charges the winner's bid. GSP charges the minimum CPM that would still
have won against the runner-up on eCPM (`runnerUpEcpm / winnerPctr + 1 micro`), clamped to `[floor, bid]`.
`PricingArms` assigns each request to an arm by `hash(requestId) mod totalWeight`, so a replayed request always lands in the
same arm, and records auctions, awards and revenue per arm (`LongAdder`s, no locks). `PRICING=first|gsp|ab` selects the mode.

## Notes
Revenue per auction is booked at award time and confirmed revenue only when a de-duplicated signed impression arrives; the
dashboard shows both. In the synthetic population advertisers bid a fixed value, so first-price is expected to out-earn GSP in
the short run; that comparison says nothing about equilibrium behaviour once advertisers shade bids. Bid shading is a stretch
item and not implemented.

## Consequences
Prices are integers in micros of CPM; the per-impression charge is `max(1, price / 1000)` micros, so very low CPMs round up.
