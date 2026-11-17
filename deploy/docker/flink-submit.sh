#!/usr/bin/env bash
# compose 的 flink-submit 容器入口：等 JobManager 就绪 → 未在运行的作业才提交（容器重跑不会重复提交）
set -euo pipefail
JM=${JM:-flink-jobmanager:8081}
running=""
for _ in $(seq 1 60); do running=$(flink list -r -m "$JM" 2>/dev/null) && break; sleep 2; done
for job in PnlJob RiskJob; do
  name=tradesim-$(echo "$job" | sed 's/Job$//' | tr '[:upper:]' '[:lower:]')
  if echo "$running" | grep -q " $name "; then echo "$name 已在运行，跳过"; continue; fi
  flink run -m "$JM" -d -c "com.tradesim.flink.$job" /jobs/flink-jobs.jar
done
