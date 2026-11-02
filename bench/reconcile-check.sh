#!/usr/bin/env bash
# End-to-end check of exact reconciliation against the real full stack (Kafka + ClickHouse + Redis).
# 1. fresh stack with a short token lifetime so credits become visible within minutes
# 2. sample booked / billed / drift / credited while traffic runs (credit must grow in STEADY state)
# 3. stop traffic, wait for every token to expire, then compare the credit with ground truth: booked - billed
set -euo pipefail
cd "$(dirname "$0")/.."
export APP_PORT=${APP_PORT:-8093} REPORTING_PORT=${REPORTING_PORT:-8094} PROM_PORT=${PROM_PORT:-9099} GRAFANA_PORT=${GRAFANA_PORT:-3033}
export TOKEN_TTL_MS=${TOKEN_TTL_MS:-60000} AUTOSTART_SIM_RATE=${AUTOSTART_SIM_RATE:-300}
F="docker compose -f deploy/compose.full.yml"
OUT=bench/results/reconcile; mkdir -p "$OUT"

$F down -v > /dev/null 2>&1 || true
$F up -d --build > "$OUT/up.log" 2>&1
for _ in $(seq 1 90); do curl -sf "localhost:$APP_PORT/actuator/health" >/dev/null 2>&1 && break; sleep 3; done
login() { curl -s -X POST "localhost:$APP_PORT/api/auth/login" -H 'content-type: application/json' -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])"; }
snap() { curl -s -H "Authorization: Bearer $(login)" "localhost:$APP_PORT/api/snapshot"; }
line() { python -c "
import sys,json;d=json.load(sys.stdin);r=d['reconciliation']
print('t=%4ds booked=%11d billed=%11d drift=%10d unbillable=%10d credited=%10d runs=%d err=%r' % ($(date +%s)-$T0, d['totalSpentMicros'], d['totalBilledMicros'], r['driftMicros'], r['unbillableMicros'], r['creditedMicros'], r['runs'], r['lastError']))"; }

T0=$(date +%s)
echo "== traffic running (token lifetime ${TOKEN_TTL_MS} ms) ==" | tee "$OUT/samples.txt"
for _ in 1 2 3 4 5 6; do sleep 60; snap | line | tee -a "$OUT/samples.txt"; done

curl -s -X POST -H "Authorization: Bearer $(login)" "localhost:$APP_PORT/api/sim/stop" > /dev/null
echo "== traffic stopped; waiting for tokens to expire and two reconcile runs ==" | tee -a "$OUT/samples.txt"
for _ in 1 2 3 4; do sleep 60; snap | line | tee -a "$OUT/samples.txt"; done

snap > "$OUT/final-snapshot.json"
export MSYS_NO_PATHCONV=1
CH=$(docker exec clearline-full-clickhouse-1 clickhouse-client -u clearline --password clearline-dev -q "SELECT sum(spend_micros) FROM clearline.ad_events WHERE type='imp' AND flag=''")
python - <<EOF | tee -a "$OUT/samples.txt"
import json
d = json.load(open("$OUT/final-snapshot.json")); r = d["reconciliation"]; cs = d["campaigns"]
booked = sum(c["spentMicros"] for c in cs)          # live campaigns only: removed campaigns are no longer reconciled
billed = sum(c["billedMicros"] for c in cs)
over = [c["id"] for c in cs if c["billedMicros"] > c["spentMicros"]]
print("== ground truth (live campaigns) ==")
print("booked (lease spend)      :", booked)
print("billed (app ledger)       :", billed)
print("booked - billed           :", booked - billed)
print("credited by the reconciler:", r["creditedMicros"])
print("difference                :", (booked - billed) - r["creditedMicros"], "(0 = exact)")
print("campaigns with billed > booked:", len(over))
print("billed total incl. campaigns removed at startup:", d["totalBilledMicros"], "| billed in ClickHouse (all):", int("$CH"))
EOF
