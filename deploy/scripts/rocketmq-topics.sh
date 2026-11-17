#!/usr/bin/env bash
# 预建 RocketMQ topic。不预建时 topic 由首条消息自动创建，消费者要等下一次路由刷新（默认 30s）
# 才能订阅到，表现为首笔撤单解冻延迟十几秒。
set -euo pipefail
for t in ORDER_FREEZE TRADE_SETTLE ORDER_UNFREEZE ORDER_EXPIRE; do
  docker exec tradesim-rmq-broker-1 sh mqadmin updateTopic -n rmq-namesrv:9876 -c DefaultCluster -t "$t" -r 4 -w 4 >/dev/null
  echo "topic $t ready"
done
