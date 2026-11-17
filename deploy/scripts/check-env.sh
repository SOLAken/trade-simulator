#!/usr/bin/env bash
# 部署前环境自检
#   MODE=docker  纯 Docker 部署（只需要 Docker）
#   MODE=dev     本机开发（默认）：另需 JDK 21、Maven、Node、jq
#   MODE=k8s     kind 部署：本机开发所需 + kind、kubectl，内存 ≥ 12GB
set -uo pipefail
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
FAIL=0
MODE=${MODE:-dev}
[ "$MODE" = k8s ] && NEED_GB=${NEED_GB:-12}

echo "Docker"
if docker info >/dev/null 2>&1; then
  ok "Docker $(docker version -f '{{.Server.Version}}' 2>/dev/null)"
  mem=$(docker info -f '{{.MemTotal}}' 2>/dev/null || echo 0)
  gb=$((mem / 1024 / 1024 / 1024))
  need=${NEED_GB:-10}
  [ "$gb" -ge "$need" ] && ok "Docker 可用内存 ${gb}GB" || warn "Docker 可用内存 ${gb}GB，建议 ≥ ${need}GB（Docker Desktop → Settings → Resources）"
  if cv=$(docker compose version --short 2>/dev/null); then
    major=${cv%%.*}; rest=${cv#*.}; minor=${rest%%.*}
    if [ "${major:-0}" -gt 2 ] 2>/dev/null || { [ "${major:-0}" -eq 2 ] && [ "${minor:-0}" -ge 20 ]; } 2>/dev/null; then ok "docker compose $cv"
    else bad "docker compose $cv 过旧，需要 v2.20+（service_completed_successfully、--wait 等特性）"; fi
  else bad "缺少 docker compose v2"; fi
else
  bad "Docker 未运行或未安装"
fi

if [ "$MODE" = docker ]; then
  echo "验证工具"
  command -v curl >/dev/null && ok "curl" || bad "缺少 curl"
  command -v jq   >/dev/null && ok "jq" || warn "缺少 jq：部署本身不需要，但 make smoke 冒烟测试需要"
else
  echo "本机开发"
  if ( source "$(dirname "$0")/lib.sh" ) 2>/dev/null; then ok "JDK $(source "$(dirname "$0")/lib.sh"; java_major "$JAVA_HOME") ($(source "$(dirname "$0")/lib.sh"; echo "$JAVA_HOME"))"
  else bad "未找到 JDK 21+"; fi
  command -v mvn  >/dev/null && ok "Maven $(mvn -v 2>/dev/null | awk 'NR==1{print $3}')" || bad "缺少 Maven 3.9+"
  command -v node >/dev/null && ok "Node $(node -v)" || bad "缺少 Node 18+（前端）"
  command -v jq   >/dev/null && ok "jq" || warn "缺少 jq（smoke.sh 等验证脚本需要）"
fi

if [ "$MODE" = k8s ]; then
  echo "Kubernetes"
  command -v kind    >/dev/null && ok "kind $(kind version 2>/dev/null | awk '{print $2}')" || bad "缺少 kind（macOS: brew install kind）"
  command -v kubectl >/dev/null && ok "kubectl" || bad "缺少 kubectl（macOS: brew install kubectl）"
fi

echo "端口"
busy=""
PORTS="3306 3307 5432 6379 8848 9092 9876 10911 8080 5173"
[ "$MODE" = docker ] || PORTS="$PORTS 8081 8082 8083 8084 8085 8086"
[ "$MODE" = k8s ] && PORTS="$PORTS 18080"
for p in $PORTS; do
  if lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1; then busy="$busy $p"; fi
done
[ -z "$busy" ] && ok "所需端口空闲" || warn "已被占用:${busy}（如果是本项目已在运行可忽略）"

[ $FAIL = 0 ] && echo "环境检查通过" || { echo "环境检查未通过"; exit 1; }
