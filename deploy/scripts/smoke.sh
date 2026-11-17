#!/usr/bin/env bash
# 端到端冒烟：注册/登录 → 开户 → 市价买入 → 幂等重放 → T+1 卖出被拒 → 资金不足被拒 → Redis 与 MySQL 账本一致
# 依赖：jq、网关 8080、所有服务已启动、市场处于交易时段
set -euo pipefail
GW=${GW:-http://localhost:8080}
USER=${SMOKE_USER:-smoke_$RANDOM}
CID=${CID:-1}
SYM=${SYM:-SIM004}
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; exit 1; }
post() { curl -s -X POST "$GW$1" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" "${@:2}"; }
get()  { curl -s "$GW$1" -H "Authorization: Bearer $TOKEN"; }

echo "▶ 用户 $USER"
TOKEN=$(curl -s -X POST "$GW/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"secret123\"}" | jq -r .data.token)
[ "$TOKEN" != null ] && pass "注册登录" || fail "注册失败"

[ "$(post "/api/account/open?contestId=$CID" | jq -r .code)" = 0 ] && pass "开户（初始资金 100 万）" || fail "开户失败"

# 休市窗口里等开盘；离收盘不足 20 秒时也等下一个交易日，避免测试中途休市
for _ in $(seq 1 120); do
  C=$(curl -s "$GW/api/market/clock")
  OPEN=$(echo "$C" | jq .data.open)
  LEFT=$(( ($(echo "$C" | jq .data.closeAt) - $(echo "$C" | jq .data.serverTime)) / 1000 ))
  [ "$OPEN" = true ] && [ "$LEFT" -gt 20 ] && break
  sleep 1
done
sleep 1   # 等第一笔 tick

echo "▶ 市价买入 1000 股 $SYM"
KEY=$(uuidgen)
BUY='{"contestId":'$CID',"symbol":"'$SYM'","side":1,"type":1,"qty":1000}'
R1=$(post /api/trade/orders -H "Idempotency-Key: $KEY" -d "$BUY")
OID=$(echo "$R1" | jq -r .data.id)
[ "$(echo "$R1" | jq -r .data.status)" = 2 ] && pass "全部成交 order=$OID" || fail "未成交: $R1"

R2=$(post /api/trade/orders -H "Idempotency-Key: $KEY" -d "$BUY")
[ "$(echo "$R2" | jq -r .data.id)" = "$OID" ] && pass "同一 Idempotency-Key 重放返回同一订单" || fail "幂等失败: $R2"

OV=$(get "/api/account/overview?contestId=$CID")
echo "$OV" | jq -c '.data | {cash, frozen, marketValue, totalAsset, returnPct, pos: .positions[0] | {qty, avail, pending}}'
[ "$(echo "$OV" | jq '.data.positions[0].pending')" = 1000 ] && pass "持仓 1000 股，今日买入不可卖（T+1）" || fail "持仓异常"
[ "$(echo "$OV" | jq '.data.frozen')" = 0 ] && pass "订单终结，多冻结的资金已全部退回" || fail "冻结未清零"

echo "▶ 边界"
R3=$(post /api/trade/orders -H "Idempotency-Key: $(uuidgen)" -d '{"contestId":'$CID',"symbol":"'$SYM'","side":2,"type":1,"qty":1000}')
[ "$(echo "$R3" | jq -r .code)" = 2002 ] && pass "T+1：当日买入卖出被拒（$(echo "$R3" | jq -r .msg)）" || fail "T+1 未生效: $R3"
R4=$(post /api/trade/orders -H "Idempotency-Key: $(uuidgen)" -d '{"contestId":'$CID',"symbol":"SIM002","side":1,"type":1,"qty":100000}')
[ "$(echo "$R4" | jq -r .code)" = 2001 ] && pass "资金不足被拒" || fail "资金校验失败: $R4"
R5=$(curl -s -X POST "$GW/api/trade/orders" -H 'X-User-Id: 1' -H "Idempotency-Key: x" -d "$BUY")
[ "$(echo "$R5" | jq -r .code)" = 401 ] && pass "伪造 X-User-Id 被网关拦截" || fail "鉴权绕过: $R5"

echo "▶ 撮合引擎：限价挂单 / 撤单"
Q=$(curl -s "$GW/api/market/quote/SIM001" | jq .data)
LOW=$(echo "$Q" | jq .limitDown)
R6=$(post /api/trade/orders -H "Idempotency-Key: $(uuidgen)" -d '{"contestId":'$CID',"symbol":"SIM001","side":1,"type":2,"qty":200,"price":'$LOW'}')
LID=$(echo "$R6" | jq -r .data.id)
[ "$(echo "$R6" | jq .data.status)" = 0 ] && pass "跌停价限价买单挂单中 order=$LID" || fail "挂单失败: $R6"
FROZEN=$(get "/api/account/overview?contestId=$CID" | jq .data.frozen)
[ "$FROZEN" -gt 0 ] && pass "挂单冻结资金 $FROZEN 分" || fail "未冻结"
[ "$(curl -s -X DELETE "$GW/api/trade/orders/$LID" -H "Authorization: Bearer $TOKEN" | jq .data)" = true ] && pass "撤单成功" || fail "撤单失败"
[ "$(get "/api/account/overview?contestId=$CID" | jq .data.frozen)" = 0 ] && pass "撤单后冻结资金全部退回" || fail "撤单未解冻"
[ "$(curl -s -X DELETE "$GW/api/trade/orders/$LID" -H "Authorization: Bearer $TOKEN" | jq .data)" = false ] && pass "重复撤单无副作用" || fail "重复撤单异常"
OV=$(get "/api/account/overview?contestId=$CID")

echo "▶ 账本一致性（等待 RocketMQ 消费）"
REDIS_CASH=$(echo "$OV" | jq .data.cash)
for i in $(seq 1 30); do
  L=$(get "/api/account/ledger?contestId=$CID")
  [ "$(echo "$L" | jq .data.account.cash)" = "$REDIS_CASH" ] && [ "$(echo "$L" | jq .data.account.frozen)" = 0 ] && break
  sleep 1
done
echo "  （账本收敛用时 ~${i}s）"
SQL_CASH=$(echo "$L" | jq .data.account.cash)
[ "$REDIS_CASH" = "$SQL_CASH" ] && pass "Redis 可用资金 = MySQL 账本 = $SQL_CASH 分" || fail "不一致 redis=$REDIS_CASH mysql=$SQL_CASH"
[ "$(echo "$L" | jq .data.account.frozen)" = 0 ] && pass "MySQL 冻结 = 0" || fail "MySQL 冻结不为 0"
echo "全部通过"
