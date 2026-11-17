#!/usr/bin/env bash
# 启动中间件并完成初始化（幂等，可重复执行）：
#   起 infra + mq（可选 flink）→ 等健康检查 → 导入 Nacos 配置与 Sentinel 规则 → 预建 RocketMQ topic
#   ./deploy/scripts/infra-up.sh            infra + mq
#   WITH_FLINK=1 ./deploy/scripts/infra-up.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ENV_FILE=""
[ -f .env ] && { set -a; . ./.env; set +a; ENV_FILE="--env-file .env"; }
PROFILES="--profile infra --profile mq"
[ "${WITH_FLINK:-}" = 1 ] && PROFILES="$PROFILES --profile flink"
# 宿主机跑服务时 broker 宣告 127.0.0.1；纯 Docker / K8s 模式由各自入口设为 rmq-broker
export BROKER_IP=${BROKER_IP:-127.0.0.1}

COMPOSE="docker compose -f deploy/docker-compose.yml $ENV_FILE"

# 本机 / K8s 模式下服务不能同时在 compose 里运行（端口冲突，且会以同一撮合节点号重复撮合）
running=$($COMPOSE --profile infra --profile mq --profile flink --profile app --profile flink-jobs ps -q gateway user-service market-service trade-service account-service risk-service rank-service web flink-submit)
if [ -n "$running" ]; then
  echo "▶ 停止纯 Docker 模式的应用容器"
  $COMPOSE --profile infra --profile mq --profile flink --profile app --profile flink-jobs rm -sf gateway user-service market-service trade-service account-service risk-service rank-service web flink-submit >/dev/null
fi

echo "▶ 启动中间件"
$COMPOSE $PROFILES up -d --wait mysql-0 mysql-1 timescale redis nacos
$COMPOSE $PROFILES up -d

echo "▶ 导入 Nacos 配置"
./deploy/scripts/nacos-import.sh
echo "▶ 预建 RocketMQ topic"
./deploy/scripts/rocketmq-topics.sh
echo "✓ 中间件就绪"
