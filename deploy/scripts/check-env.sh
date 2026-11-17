#!/usr/bin/env bash
# 部署前环境自检。MODE=docker 只检查纯 Docker 部署所需；默认检查本机开发所需
set -uo pipefail
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
FAIL=0

echo "Docker"
if docker info >/dev/null 2>&1; then
  ok "Docker $(docker version -f '{{.Server.Version}}' 2>/dev/null)"
  mem=$(docker info -f '{{.MemTotal}}' 2>/dev/null || echo 0)
  gb=$((mem / 1024 / 1024 / 1024))
  need=${NEED_GB:-10}
  [ "$gb" -ge "$need" ] && ok "Docker 可用内存 ${gb}GB" || warn "Docker 可用内存 ${gb}GB，建议 ≥ ${need}GB（Docker Desktop → Settings → Resources）"
  docker compose version >/dev/null 2>&1 && ok "docker compose $(docker compose version --short)" || bad "缺少 docker compose v2"
else
  bad "Docker 未运行或未安装"
fi

if [ "${MODE:-dev}" != docker ]; then
  echo "本机开发"
  if ( source "$(dirname "$0")/lib.sh" ) 2>/dev/null; then ok "JDK $(source "$(dirname "$0")/lib.sh"; java_major "$JAVA_HOME") ($(source "$(dirname "$0")/lib.sh"; echo "$JAVA_HOME"))"
  else bad "未找到 JDK 21+"; fi
  command -v mvn  >/dev/null && ok "Maven $(mvn -v 2>/dev/null | awk 'NR==1{print $3}')" || bad "缺少 Maven 3.9+"
  command -v node >/dev/null && ok "Node $(node -v)" || bad "缺少 Node 18+（前端）"
  command -v jq   >/dev/null && ok "jq" || warn "缺少 jq（smoke.sh 等验证脚本需要）"
fi

echo "端口"
busy=""
for p in 3306 3307 5432 6379 8848 9092 9876 10911 8080 8081 8082 8083 8084 8085 8086 5173; do
  if lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1; then busy="$busy $p"; fi
done
[ -z "$busy" ] && ok "所需端口空闲" || warn "已被占用:${busy}（如果是本项目已在运行可忽略）"

[ $FAIL = 0 ] && echo "环境检查通过" || { echo "环境检查未通过"; exit 1; }
