#!/usr/bin/env bash
# Starts the app, opens the dashboard and runs the compressed day (24 simulated hours in ~20 minutes).
set -euo pipefail
cd "$(dirname "$0")/.."
PORT=${PORT:-8080}
JAR=services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar
PORT=$PORT nohup java -Xmx768m -jar "$JAR" > /tmp/adexchange-demo.log 2>&1 &
for _ in $(seq 1 60); do curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
TOKEN=$(curl -s -X POST "http://localhost:$PORT/api/auth/login" -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASSWORD:-admin123}\"}" | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s -X POST "http://localhost:$PORT/api/sim/start" -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"scenario":"compressed-day","rate":600}' > /dev/null
URL="http://localhost:$PORT/live"
echo "Dashboard: $URL   (admin / ${ADMIN_PASSWORD:-admin123})"
(command -v xdg-open >/dev/null && xdg-open "$URL") || (command -v open >/dev/null && open "$URL") || (command -v start >/dev/null && start "$URL") || true
