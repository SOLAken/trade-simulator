#!/usr/bin/env bash
# 启动中间件并完成初始化（幂等，可重复执行）：
#   起 infra + mq（可选 flink）→ 等健康检查 → 导入 Nacos 配置与 Sentinel 规则 → 预建 RocketMQ topic
#   ./deploy/scripts/infra-up.sh            infra + mq
#   WITH_FLINK=1 ./deploy/scripts/infra-up.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
PROFILES="--profile infra --profile mq"
[ "${WITH_FLINK:-}" = 1 ] && PROFILES="$PROFILES --profile flink"
# 宿主机跑服务时 broker 宣告 127.0.0.1；纯 Docker / K8s 模式由各自入口设为 rmq-broker
export BROKER_IP=${BROKER_IP:-127.0.0.1}

echo "▶ 启动中间件"
docker compose -f deploy/docker-compose.yml $PROFILES up -d --wait --wait-timeout 300 \
  mysql-0 mysql-1 timescale redis nacos
docker compose -f deploy/docker-compose.yml $PROFILES up -d

echo "▶ 导入 Nacos 配置"
./deploy/scripts/nacos-import.sh
echo "▶ 预建 RocketMQ topic"
./deploy/scripts/rocketmq-topics.sh
echo "✓ 中间件就绪"
