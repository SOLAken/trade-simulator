#!/usr/bin/env bash
# 构建并提交 Flink 作业（已在运行的同名作业先取消）。Web UI: http://localhost:8090
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$(dirname "$0")/lib.sh"
mvn -q -B package -pl flink-jobs -am -DskipTests
JM=tradesim-flink-jobmanager-1
JAR=/jobs/flink-jobs-0.1.0-SNAPSHOT.jar
for name in tradesim-pnl tradesim-risk; do
  id=$(docker exec $JM flink list -r 2>/dev/null | grep " $name " | awk '{print $4}' || true)
  [ -n "$id" ] && docker exec $JM flink cancel "$id" >/dev/null && echo "cancelled $name ($id)"
done
docker exec $JM flink run -d -c com.tradesim.flink.PnlJob  $JAR | grep -i 'jobid'
docker exec $JM flink run -d -c com.tradesim.flink.RiskJob $JAR | grep -i 'jobid'
docker exec $JM flink list -r
