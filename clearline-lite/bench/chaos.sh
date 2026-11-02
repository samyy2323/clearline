#!/usr/bin/env bash
# Fault injection under open-loop load. Phases (seconds after warm-up): baseline 0-30, Redis down 30-60, recovery 60-90,
# +200 ms latency on shared stores 90-120, recovery 120-150. Raw per-second series come from the app's own
# /api/timeseries and are saved with the loadgen result. Faults are injected in-process (FaultInjector on the
# lease store and frequency-cap store), not at the network layer; see docs/adr/0007-fault-injection.md.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT=${PORT:-8091}; RATE=${RATE:-3000}; OUT=bench/results/chaos; mkdir -p "$OUT"
APP_URL=http://localhost:$PORT
JAR=services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar
PORT=$PORT SEED_BUDGET_SCALE=${SEED_BUDGET_SCALE:-100000} EVENTS_LOG= LEASE_CHUNK_MICROS=${LEASE_CHUNK_MICROS:-200000} LEASE_TTL_MS=${LEASE_TTL_MS:-20000} \
  nohup java -Xms1g -Xmx1g -jar "$JAR" > "$OUT/app.log" 2>&1 &
for _ in $(seq 1 60); do curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1 && break; sleep 1; done; sleep 5
TOKEN=$(curl -s -X POST "$APP_URL/api/auth/login" -H 'content-type: application/json' -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASSWORD:-admin123}\"}" | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
fault() { curl -s -X POST "$APP_URL/api/sim/faults" -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d "$1" > /dev/null; echo "t=$(date +%T) fault $1" | tee -a "$OUT/events.log"; }
( sleep 45; fault '{"redisDown":true}'; sleep 30; fault '{"redisDown":false}'; sleep 30; fault '{"latencyMs":200}'; sleep 30; fault '{"latencyMs":0}' ) &
java -jar simulator/target/simulator-1.0.0-SNAPSHOT.jar --url "$APP_URL" --scenario steady --rate "$RATE" --duration 150 --warmup 15 --connections ${CONNECTIONS:-1024} --label chaos --out "$OUT/loadgen.json" | tee "$OUT/loadgen.log" >/dev/null
curl -s -H "Authorization: Bearer $TOKEN" "$APP_URL/api/timeseries" > "$OUT/timeseries.json"
curl -s "$APP_URL/actuator/prometheus" > "$OUT/prometheus.txt"
python bench/plot_chaos.py "$OUT" || echo "plot skipped (matplotlib missing?)"
PID=$(netstat -ano 2>/dev/null | grep ":$PORT .*LISTENING" | awk '{print $5}' | head -1 || true); [ -n "$PID" ] && taskkill //PID "$PID" //F >/dev/null 2>&1 || pkill -f auction-engine || true
echo "chaos results in $OUT"
