#!/usr/bin/env bash
# 同一压测场景下对比 trade-service 的 G1 与分代 ZGC（输出到 .run/gc-compare/）
#   ./deploy/scripts/gc-compare.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT=.run/gc-compare
mkdir -p "$OUT"
PROBE=deploy/loadtest/warmup.js
GW=http://localhost:8080

wait_open() {   # 等到交易时段且剩余 ≥ 120 秒
  until c=$(curl -s "$GW/api/market/clock") && [ "$(echo "$c" | jq .data.open)" = true ] \
        && [ $(( ($(echo "$c" | jq .data.closeAt) - $(echo "$c" | jq .data.serverTime)) / 1000 )) -ge 120 ]; do
    sleep 3
  done
}

for gc in zgc g1; do
  case $gc in
    zgc) opts="-XX:+UseZGC -XX:+ZGenerational" ;;
    g1)  opts="-XX:+UseG1GC -XX:MaxGCPauseMillis=50" ;;
  esac
  echo "=== $gc ($opts)"
  JAVA_GC="$opts" HEAP=512m ./deploy/scripts/run-local.sh trade-service >/dev/null
  wait_open
  k6 run --quiet "$PROBE" >/dev/null 2>&1 || true          # 预热 JIT
  START=$(grep -o '^\[[0-9.]*s\]' .run/logs/trade-service-gc.log | tail -1 | tr -d '[]s')
  k6 run --quiet --summary-export "$OUT/$gc-k6.json" deploy/loadtest/open-burst.js > "$OUT/$gc-k6.txt" 2>&1 || true
  ./deploy/scripts/trade-timers.sh > "$OUT/$gc-timers.txt"
  python3 deploy/scripts/gc-report.py .run/logs/trade-service-gc.log "$START" > "$OUT/$gc-gc.txt"
  grep -E 'orders_|accepted_latency' "$OUT/$gc-k6.txt" | grep -v '✓\|✗' || true
  cat "$OUT/$gc-gc.txt"
done
