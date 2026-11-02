# Runs that must not be used as results

`opt-after/` and `profile/baseline/loadgen.json`, `profile/optimized/loadgen.json` were recorded while an unrelated local program
(`llama-server`) was using about 600% CPU (half of the 12-core machine). Their latency and error figures reflect that contention,
not the code. The profile recordings are still valid for *where time and allocation go* (hot spots, bytes allocated per request),
which does not depend on the machine being quiet. Valid latency results: `baseline-steady-5000/`, `exp-*/`, `chaos/`.
Latency for the profile-driven optimizations has to be re-measured on a quiet machine (interleaved old/new runs).
