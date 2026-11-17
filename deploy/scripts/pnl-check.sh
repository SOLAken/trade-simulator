#!/usr/bin/env bash
# 对比 Flink 实时盈亏（rank.update）与账户服务按 Redis 实时计算的总资产
#   USERNAME=alice ./deploy/scripts/pnl-check.sh
set -euo pipefail
GW=${GW:-http://localhost:8080}
NAME=${USERNAME:-alice}
CID=${CID:-1}
LOGIN=$(curl -s -X POST "$GW/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$NAME\",\"password\":\"secret123\"}")
TOKEN=$(echo "$LOGIN" | jq -r .data.token)
UID_=$(echo "$LOGIN" | jq -r .data.userId)
INIT=$(curl -s "$GW/api/account/overview?contestId=$CID" -H "Authorization: Bearer $TOKEN" | jq .data.initCash)

for n in 1 2 3; do
  # 只读每个分区最新到达的消息（topic 持续写入，--from-beginning 会一直读不完）
  FL=""
  for p in 0 1 2 3; do
    m=$(docker exec tradesim-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
          --topic rank.update --partition $p --offset latest --max-messages 40 --timeout-ms 3000 2>/dev/null \
          | grep "\"userId\":$UID_," | tail -1 || true)
    [ -n "$m" ] && FL=$m
  done
  [ -z "$FL" ] && { echo "3 秒内没有该用户的 rank.update（空仓或价格未变）"; continue; }
  OV=$(curl -s "$GW/api/account/overview?contestId=$CID" -H "Authorization: Bearer $TOKEN" | jq .data.totalAsset)
  PNL=$(echo "$FL" | jq .pnl)
  TS=$(echo "$FL" | jq .ts)
  FLINK_TOTAL=$((INIT + PNL))
  DIFF=$((OV - FLINK_TOTAL))
  AGE=$(( $(date +%s) * 1000 - TS ))
  echo "Flink 总资产 $FLINK_TOTAL | 账户 $OV | 差 $DIFF 分 | Flink 数据距今 ${AGE}ms"
done
