#!/bin/sh
# 把 deploy/nacos 下的配置发布到 Nacos（幂等，可重复执行；宿主机或 compose 的 nacos-init 容器内均可运行）
#   *.yaml           → group DEFAULT_GROUP（应用配置，@ConfigurationProperties 热更新）
#   sentinel/*.json  → group SENTINEL_GROUP（Sentinel 规则数据源）
set -eu
NACOS=${NACOS:-http://127.0.0.1:8848}
DIR=${NACOS_CONFIG_DIR:-"$(cd "$(dirname "$0")/../nacos" && pwd)"}
i=0
until curl -sf "$NACOS/nacos/v1/console/health/readiness" >/dev/null; do
  i=$((i + 1)); [ $i -ge 60 ] && { echo "Nacos 未就绪：$NACOS" >&2; exit 1; }
  sleep 2
done
publish() {
  curl -sf -X POST "$NACOS/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$(basename "$1")" --data-urlencode "group=$2" \
    --data-urlencode "type=$3" --data-urlencode "content@$1" >/dev/null
  echo "published $2/$(basename "$1")"
}
for f in "$DIR"/*.yaml; do publish "$f" DEFAULT_GROUP yaml; done
for f in "$DIR"/sentinel/*.json; do publish "$f" SENTINEL_GROUP json; done
