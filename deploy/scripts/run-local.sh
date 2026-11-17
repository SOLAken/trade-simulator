#!/usr/bin/env bash
# 本地（宿主机）启动/停止全部服务。日志在 .run/logs/
#   ./deploy/scripts/run-local.sh            构建并（重）启动全部服务
#   ./deploy/scripts/run-local.sh stop       停止全部服务
#   ./deploy/scripts/run-local.sh trade-service account-service   只重启指定服务（不构建）
# 注意：运行中的服务其 jar 被覆盖后会出现 NoClassDefFoundError，所以构建后必须全部重启。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
LOGS="$ROOT/.run/logs"
mkdir -p "$LOGS"
ALL=(gateway user-service market-service trade-service account-service risk-service rank-service)
port() {   # macOS 自带 bash 3.2 不支持关联数组
  case $1 in
    gateway) echo 8080 ;; user-service) echo 8081 ;; market-service) echo 8082 ;; trade-service) echo 8083 ;;
    account-service) echo 8084 ;; risk-service) echo 8085 ;; rank-service) echo 8086 ;;
  esac
}

stop() {
  for m in "$@"; do pkill -f "$m-0.1.0-SNAPSHOT.jar" 2>/dev/null || true; done
  # 必须等旧进程真正退出：优雅停机可能要十几秒，新进程提前启动会因端口占用直接退出
  for m in "$@"; do
    for _ in $(seq 1 30); do pgrep -f "$m-0.1.0-SNAPSHOT.jar" >/dev/null || break; sleep 1; done
    pkill -9 -f "$m-0.1.0-SNAPSHOT.jar" 2>/dev/null || true
  done
}

if [ "${1:-}" = stop ]; then stop "${ALL[@]}"; echo stopped; exit 0; fi

if [ $# -eq 0 ]; then
  SERVICES=("${ALL[@]}")
  stop "${SERVICES[@]}"
  mvn -q -B package -DskipTests
else
  SERVICES=("$@")
  stop "${SERVICES[@]}"
fi

for m in "${SERVICES[@]}"; do
  # JAVA_GC 可切换收集器做对比，如 JAVA_GC="-XX:+UseG1GC"；GC 日志写到 .run/logs/<服务>-gc.log
  nohup "$JAVA_HOME/bin/java" -Xms256m -Xmx${HEAP:-384m} ${JAVA_GC:--XX:+UseZGC -XX:+ZGenerational} \
    -Xlog:gc,safepoint:file="$LOGS/$m-gc.log":uptime,level \
    -jar "$m/target/$m-0.1.0-SNAPSHOT.jar" > "$LOGS/$m.log" 2>&1 &
done

for m in "${SERVICES[@]}"; do
  p=$(port "$m")
  for _ in $(seq 1 60); do
    curl -sf "localhost:$p/actuator/health" >/dev/null && break
    sleep 1
  done
  printf '%-16s %s\n' "$m" "$(curl -s "localhost:$p/actuator/health" || echo DOWN)"
done
