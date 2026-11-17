#!/usr/bin/env bash
# 构建全部服务镜像（tag: tradesim/<服务>:<git 短哈希> 与 :latest）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
TAG=$(git rev-parse --short HEAD)
[ "${SKIP_BUILD:-}" = 1 ] || mvn -q -B package -DskipTests
for m in gateway user-service market-service trade-service account-service risk-service rank-service; do
  docker build -q -f deploy/Dockerfile --build-arg MODULE=$m -t tradesim/$m:$TAG -t tradesim/$m:latest . >/dev/null
  printf '%-16s %s\n' "$m" "$(docker image inspect tradesim/$m:latest --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1e6}')"
done
