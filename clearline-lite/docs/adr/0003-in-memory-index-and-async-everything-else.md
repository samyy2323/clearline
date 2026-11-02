# ADR 0003: Targeting from an in-memory index; Kafka and databases stay off the hot path

Status: accepted

## Context
A 50 ms budget leaves no room for a database or broker round trip per auction, and the dependencies that fail most (network
stores, brokers) should not be able to fail an auction.

## Decision
- Retrieval uses an immutable inverted index (`CampaignIndex`): per-dimension posting lists as `BitSet`s over dense campaign
  ordinals (geo, device, site category, segment, hour), with wildcard campaigns folded in so a query is a few ANDs. A change
  builds a new snapshot and swaps one volatile reference (`IndexHolder`).
- Scoring is an in-process hashed logistic regression; weights hot-reload from a file by swapping one reference.
- Budget is spent from a local lease (ADR 0001).
- The only network call on the auction path is the frequency-cap batch lookup (one Lua call), which fails open and flags the
  response degraded.
- Events (win, impression, click) are written off the auction thread to a bounded queue; when it is full they are dropped and
  counted, never blocked on.

## Alternatives
Per-request database lookups, or Kafka consumer lag as a coupling to correctness: rejected for latency and blast radius.

## Consequences
Index updates are eventually consistent (outbox relay every 100 ms in lite mode). Rebuilding the whole snapshot per change is
O(campaigns), fine for thousands and a known limit for millions; the next step would be copy-on-write posting lists.
Per-stage deadlines (10 ms retrieval, 10 ms scoring, 10 ms budget, 35 ms total) turn a slow stage into a no-bid, never a late bid.
