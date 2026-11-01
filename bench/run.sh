#!/usr/bin/env bash
# Reproducible benchmark runner. Starts the app (unless APP_URL points at one), drives it with the open-loop
# generator and stores raw outputs under bench/results/<tag>/.
#
#   bench/run.sh                         # steady 5000 req/s, 60 s
#   RATE=10000 SCENARIO=ramp bench/run.sh
#   TAG=virtual-threads VIRTUAL_THREADS=true bench/run.sh
#
# Hardware, JVM flags and the loadgen's own CPU are saved with every result (the loadgen shares this machine).
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO=${SCENARIO:-steady}
RATE=${RATE:-5000}
DURATION=${DURATION:-60}
WARMUP=${WARMUP:-15}
CONNECTIONS=${CONNECTIONS:-128}
TAG=${TAG:-$SCENARIO-$RATE}
PORT=${PORT:-8091}
HEAP=${HEAP:-1g}
JVM_FLAGS=${JVM_FLAGS:-}
APP_JAR=${APP_JAR:-services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar}
LOADGEN_JAR=${LOADGEN_JAR:-simulator/target/simulator-1.0.0-SNAPSHOT.jar}
OUT=bench/results/$TAG
mkdir -p "$OUT"

started=""
if [ -z "${APP_URL:-}" ]; then
  APP_URL=http://localhost:$PORT
  # budgets scaled up so campaigns do not run dry during a long run (we are measuring the auction path)
  PORT=$PORT SEED_BUDGET_SCALE=${SEED_BUDGET_SCALE:-100000} EVENTS_LOG= VIRTUAL_THREADS=${VIRTUAL_THREADS:-true} \
    nohup java -Xms$HEAP -Xmx$HEAP $JVM_FLAGS -jar "$APP_JAR" > "$OUT/app.log" 2>&1 &
  started=$!
  for _ in $(seq 1 60); do curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  sleep 5
fi

{
  echo "tag=$TAG scenario=$SCENARIO rate=$RATE duration=$DURATION warmup=$WARMUP connections=$CONNECTIONS"
  echo "heap=$HEAP jvm_flags=$JVM_FLAGS virtual_threads=${VIRTUAL_THREADS:-true}"
  echo "java=$(java -version 2>&1 | head -1)"
  echo "date=$(date -u +%FT%TZ)"
  git rev-parse HEAD 2>/dev/null | sed 's/^/commit=/' || true
} > "$OUT/env.txt"

java -jar "$LOADGEN_JAR" --url "$APP_URL" --scenario "$SCENARIO" --rate "$RATE" --duration "$DURATION" \
  --warmup "$WARMUP" --connections "$CONNECTIONS" --label "$TAG" --out "$OUT/loadgen.json" | tee "$OUT/loadgen.log"

# server-side view of the same run (needs an admin login; defaults are the dev credentials)
TOKEN=$(curl -s -X POST "$APP_URL/api/auth/login" -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASSWORD:-admin123}\"}" | python -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null || true)
if [ -n "$TOKEN" ]; then
  curl -s -H "Authorization: Bearer $TOKEN" "$APP_URL/api/snapshot" > "$OUT/server-snapshot.json" || true
fi
curl -s "$APP_URL/actuator/prometheus" > "$OUT/prometheus.txt" || true

if [ -n "$started" ]; then
  kill "$started" 2>/dev/null || true
  # Windows (Git Bash): kill by port as the java PID differs from the shell's job PID
  PID=$(netstat -ano 2>/dev/null | grep ":$PORT .*LISTENING" | awk '{print $5}' | head -1 || true)
  [ -n "$PID" ] && taskkill //PID "$PID" //F >/dev/null 2>&1 || true
fi
echo "results in $OUT"
