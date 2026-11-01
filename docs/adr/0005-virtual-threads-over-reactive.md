# ADR 0005: Servlet + virtual threads for the gateway, behind a flag

Status: accepted; WebFlux comparison not done

## Context
The gateway can run on an event loop (Netty/WebFlux) or on virtual threads. The auction path is CPU-bound and non-blocking
apart from one short frequency-cap call, so the model matters less than for I/O-heavy services.

## Decision
Spring MVC on Tomcat with `spring.threads.virtual.enabled` controlled by `VIRTUAL_THREADS` (default true), so the model can be
switched per run and benchmarked (`bench/run.sh` records the setting). Plain blocking code is easier to reason about and to
profile than reactive pipelines.

## Consequences
A WebFlux implementation was not written, so this ADR does not claim reactive is slower; it records that virtual threads vs
platform threads is the comparison actually measured. See `docs/optimization.md` for what was run.
