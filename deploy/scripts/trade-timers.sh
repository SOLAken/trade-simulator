#!/usr/bin/env bash
# 打印 trade-service 下单各阶段耗时（Micrometer 计时器：次数 / 平均 / 最大，单位 ms）
T=${TRADE:-http://localhost:8083}
show() {
  local name=$1 tag=$2
  for v in $(curl -s "$T/actuator/metrics/$name" | jq -r ".availableTags[] | select(.tag==\"$tag\") | .values[]"); do
    curl -s "$T/actuator/metrics/$name?tag=$tag:$v" | jq -r --arg n "$name[$v]" \
      '[.measurements[] | {(.statistic): .value}] | add | "\($n)\tcount=\(.COUNT|floor)\tavg=\((.TOTAL_TIME/.COUNT*1000*100|floor)/100)ms\tmax=\((.MAX*1000*100|floor)/100)ms"'
  done
}
show order.phase phase; show tx.send biz; show tx.local biz
