#!/usr/bin/env bash
# Config experiments at the same load as the baseline. Each run starts a fresh JVM; results land in bench/results/exp-*/.
set -euo pipefail
cd "$(dirname "$0")/.."
export RATE=${RATE:-5000} DURATION=${DURATION:-30} WARMUP=${WARMUP:-15}
TAG=exp-baseline-repeat bench/run.sh > /dev/null
TAG=exp-platform-threads VIRTUAL_THREADS=false bench/run.sh > /dev/null
TAG=exp-parallel-gc JVM_FLAGS="-XX:+UseParallelGC" bench/run.sh > /dev/null
TAG=exp-zgc JVM_FLAGS="-XX:+UseZGC -XX:+ZGenerational" bench/run.sh > /dev/null
echo done
