# 脚本公共函数：source "$(dirname "$0")/lib.sh"
# 定位 JDK 21+：已设置的 JAVA_HOME → macOS java_home → Homebrew → PATH 中的 java
java_major() { "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}'; }

find_java() {
  local c
  for c in "${JAVA_HOME:-}" \
           "$( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21+ 2>/dev/null )" \
           /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 \
           "$( command -v java >/dev/null && dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")" )"; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    [ "$(java_major "$c")" -ge 21 ] 2>/dev/null && { echo "$c"; return 0; }
  done
  return 1
}

JAVA_HOME="$(find_java)" || { echo "✗ 未找到 JDK 21+：请安装后设置 JAVA_HOME（或改用纯 Docker 部署：make docker-up）" >&2; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

# 读取仓库根目录的 .env（可选），让脚本与 docker compose 使用同一份口令
ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
