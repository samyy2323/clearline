#!/usr/bin/env bash
# Records a JFR profile of the server under open-loop load. Usage: TAG=baseline bench/profile.sh
# Output: bench/results/profile/<TAG>/{recording.jfr,loadgen.json}; analyze with python bench/profile/analyze.py <dir>
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=${TAG:-baseline}; RATE=${RATE:-5000}; PORT=${PORT:-8091}; OUT=bench/results/profile/$TAG
mkdir -p "$OUT"
JAR=services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar
PORT=$PORT SEED_BUDGET_SCALE=100000 EVENTS_LOG= nohup java -Xms1g -Xmx1g \
  -XX:StartFlightRecording=name=prof,settings=profile,filename="$OUT/recording.jfr",dumponexit=true \
  -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints ${JVM_FLAGS:-} -jar "$JAR" > "$OUT/app.log" 2>&1 &
for _ in $(seq 1 60); do curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break; sleep 1; done; sleep 5
java -jar simulator/target/simulator-1.0.0-SNAPSHOT.jar --url "http://localhost:$PORT" --scenario steady --rate "$RATE" \
  --duration 40 --warmup 15 --label "profile-$TAG" --out "$OUT/loadgen.json" > "$OUT/loadgen.log"
PID=$(netstat -ano | grep ":$PORT .*LISTENING" | awk '{print $5}' | head -1)
jcmd "$PID" JFR.dump name=prof filename="$OUT/recording.jfr" > /dev/null
taskkill //PID "$PID" //F > /dev/null 2>&1 || kill "$PID" 2>/dev/null || true
echo "recorded $OUT/recording.jfr"
