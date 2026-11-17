#!/usr/bin/env bash
# 风控演示：模拟「挂单后立刻撤单」的刷单脚本 → Flink 识别 → risk-service 封禁 → 下单被拒
#   ./deploy/scripts/risk-demo.sh            按当前规则刷 N 轮
#   ROUNDS=4 ./deploy/scripts/risk-demo.sh   配合把 max-quick-cancels-per-30s 调小验证规则热更新
set -euo pipefail
GW=${GW:-http://localhost:8080}
ROUNDS=${ROUNDS:-12}
U="spoofer_$RANDOM"
TOKEN=$(curl -s -X POST "$GW/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"secret123\"}" | jq -r .data.token)
H=(-H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN")
curl -s -X POST "$GW/api/user/contests/1/join" "${H[@]}" >/dev/null
LOW=$(curl -s "$GW/api/market/quote/SIM003" | jq .data.limitDown)
echo "▶ $U 挂撤 $ROUNDS 轮（跌停价挂买单，0.3 秒后撤）"
for i in $(seq 1 "$ROUNDS"); do
  R=$(curl -s -X POST "$GW/api/trade/orders" "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
    -d '{"contestId":1,"symbol":"SIM003","side":1,"type":2,"qty":100,"price":'"$LOW"'}')
  OID=$(echo "$R" | jq -r '.data.id // empty')
  if [ -z "$OID" ]; then echo "  第 $i 轮下单被拒：$(echo "$R" | jq -r .msg)"; break; fi
  sleep 0.3
  curl -s -X DELETE "$GW/api/trade/orders/$OID" "${H[@]}" >/dev/null
  sleep 0.3
done
echo "▶ 等待 Flink 告警与封禁"
for _ in $(seq 1 20); do
  S=$(curl -s "$GW/api/risk/me" "${H[@]}" | jq -c .data)
  [ "$(echo "$S" | jq .blocked)" = true ] && break
  sleep 1
done
echo "  风控状态：$S"
R=$(curl -s -X POST "$GW/api/trade/orders" "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"contestId":1,"symbol":"SIM003","side":1,"type":2,"qty":100,"price":'"$LOW"'}')
echo "  封禁后再下单：$(echo "$R" | jq -c '{code, msg}')"
