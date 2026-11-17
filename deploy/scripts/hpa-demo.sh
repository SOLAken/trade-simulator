#!/usr/bin/env bash
# HPA 演示：对 K8s 网关施加恒定下单压力，每 15 秒记录副本数与 CPU
#   RATE=300 DURATION=180s ./deploy/scripts/hpa-demo.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
RATE=${RATE:-300}
DURATION=${DURATION:-180s}
GW=${GW:-http://localhost:18080}
LOG=.run/hpa-demo-k6.txt
GW=$GW RATE=$RATE DURATION=$DURATION k6 run --quiet deploy/loadtest/warmup.js > "$LOG" 2>&1 &
K6=$!
hpa() {   # 当前 CPU 利用率 / 副本数（jsonpath，避免 TARGETS 列含空格导致 awk 错位）
  kubectl -n tradesim get hpa "$1" -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}%/{.status.currentReplicas}'
}
printf '%-8s %-18s %-18s\n' time "gateway cpu/副本" "trade cpu/副本"
while kill -0 $K6 2>/dev/null; do
  printf '%-8s %-18s %-18s\n' "$(date +%T)" "$(hpa gateway)" "$(hpa trade-service)"
  sleep 15
done
for _ in 1 2; do
  printf '%-8s %-18s %-18s\n' "$(date +%T)" "$(hpa gateway)" "$(hpa trade-service)"
  sleep 15
done
grep -E 'http_req_duration|http_reqs' "$LOG"
kubectl -n tradesim get pods -l app=trade-service --no-headers | awk '{print $1, $2, $3, $5}'
