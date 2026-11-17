#!/usr/bin/env bash
# 把 deploy/nacos/*.yaml 发布到 Nacos 配置中心（幂等，可重复执行）
set -euo pipefail
NACOS=${NACOS:-http://127.0.0.1:8848}
DIR="$(cd "$(dirname "$0")/../nacos" && pwd)"
for f in "$DIR"/*.yaml; do
  id=$(basename "$f")
  curl -sf -X POST "$NACOS/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$id" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@$f" >/dev/null
  echo "published $id"
done
