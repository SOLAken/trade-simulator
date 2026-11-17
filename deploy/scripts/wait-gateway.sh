#!/bin/sh
# 等网关健康：容器 Up 之后服务还要 30–60 秒注册到 Nacos
GW=${GW:-http://localhost:8080}
i=0
printf '等待网关就绪 %s ' "$GW"
until [ "$(curl -s "$GW/actuator/health" 2>/dev/null)" = '{"status":"UP"}' ]; do
  i=$((i + 1))
  [ $i -ge 90 ] && { echo; echo "✗ 3 分钟内未就绪，查看日志：make docker-logs S=gateway" >&2; exit 1; }
  printf '.'; sleep 2
done
echo " ✓"
