#!/bin/sh
# 预建 RocketMQ topic（幂等）。不预建时 topic 由首条消息自动创建，消费者要等下一次路由刷新（默认 30s）
# 才能订阅到，表现为首笔撤单解冻延迟十几秒。
#   宿主机执行：通过 docker exec 进 broker 容器调用 mqadmin
#   容器内执行（compose 的 rmq-init）：IN_CONTAINER=1，直接调用镜像自带的 mqadmin
set -eu
NAMESRV=${NAMESRV:-rmq-namesrv:9876}
BROKER=${RMQ_BROKER_CONTAINER:-tradesim-rmq-broker-1}
for t in ORDER_FREEZE TRADE_SETTLE ORDER_UNFREEZE ORDER_EXPIRE; do
  i=0
  until if [ "${IN_CONTAINER:-}" = 1 ]; then sh "${ROCKETMQ_HOME:-/home/rocketmq/rocketmq-5.3.1}/bin/mqadmin" updateTopic -n "$NAMESRV" -c DefaultCluster -t "$t" -r 4 -w 4 >/dev/null 2>&1
        else docker exec "$BROKER" sh mqadmin updateTopic -n "$NAMESRV" -c DefaultCluster -t "$t" -r 4 -w 4 >/dev/null 2>&1; fi; do
    i=$((i + 1)); [ $i -ge 30 ] && { echo "topic $t 创建失败（broker 未就绪？）" >&2; exit 1; }
    sleep 2
  done
  echo "topic $t ready"
done
