# ADR 0007: In-process fault injection instead of Toxiproxy

Status: accepted

## Decision
`FaultInjector` wraps the lease store and the frequency-cap store. "Redis down" makes both throw; "+N ms" sleeps before each
call. It is switchable from the dashboard and by `POST /api/sim/faults`; `bench/chaos.sh` drives it during open-loop load and saves
per-second series and a plot.

## Why
Toxiproxy needs a real network hop to a real Redis; the lite deployment has neither. The wrapper exercises the same code paths that
matter (renewal failure, local lease exhaustion, fail-closed spend, fail-open frequency cap with a degraded flag).

## Consequences
It does not exercise TCP-level behaviour (resets, half-open connections, partial writes). With the Redis-backed compose stack
Toxiproxy can be added in front of Redis without code changes; that was not done.
