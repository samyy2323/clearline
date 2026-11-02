#!/usr/bin/env bash
# Interleaved A/B latency comparison (A B A B ...): every run is a fresh JVM with identical flags and load, and the machine's CPU is
# logged before each run so contaminated runs can be excluded. Build the variants first with bench/ab-build.sh.
#   PAIRS=4 RATE=5000 bench/ab.sh        then       python bench/ab-summary.py
set -euo pipefail
cd "$(dirname "$0")/.."
PAIRS=${PAIRS:-4}; RATE=${RATE:-5000}; DURATION=${DURATION:-30}; WARMUP=${WARMUP:-15}; PORT=${PORT:-8091}
OUT=bench/results/ab; mkdir -p "$OUT"
: > "$OUT/runs.txt"
for i in $(seq 1 "$PAIRS"); do
  for v in A B; do
    PID=$(netstat -ano 2>/dev/null | grep ":$PORT .*LISTENING" | awk '{print $5}' | head -1 || true); [ -n "$PID" ] && taskkill //PID "$PID" //F >/dev/null 2>&1 || true
    sleep 3
    CPU=$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File bench/cpu.ps1 | tr -d '\r')
    echo "variant=$v pair=$i cpu_before=$CPU" | tee -a "$OUT/runs.txt"
    TAG="ab/$v-$i" APP_JAR=".run/$v.jar" RATE="$RATE" DURATION="$DURATION" WARMUP="$WARMUP" PORT="$PORT" bench/run.sh > /dev/null 2>&1 || echo "run $v-$i failed" | tee -a "$OUT/runs.txt"
  done
done
echo done
