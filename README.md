# Trade Simulator —— 股票交易模拟器联机游戏

房间制模拟炒股比赛：模拟行情（GBM + 跳变事件）、市价/限价/止损单、T+1、涨跌停、实时排行榜、实时风控。

## 技术栈落点
| 组件 | 用途 |
|---|---|
| Spring Cloud Gateway | 统一入口、JWT 鉴权、WebSocket 路由 |
| Nacos | 服务注册；`trade-rules.yaml` / `risk-rules.yaml` 热更新 |
| Sentinel | 开盘集中下单限流、按用户热点参数防刷 |
| Redis | 最新行情、资金/持仓热数据（Lua 原子冻结）、幂等、ZSet 排行 |
| Kafka | `market.tick` → 撮合 / 风控 / 排行榜 |
| RocketMQ | 事务消息（冻结→成交→结算）、定时消息（收盘撤单） |
| MySQL + ShardingSphere | 订单/成交/流水按 user_id 分 2 库 × 4 表，基因法 ID |
| TimescaleDB | tick hypertable + 连续聚合 K 线、回放、收益曲线 |
| Flink | 刷单识别、异常收益、实时收益率 |
| Disruptor | 撮合引擎按 symbol 分片单线程无锁 |

## 实时风控与收益（Flink）
| 作业 | 输入 | 逻辑 | 输出 |
|---|---|---|---|
| `RiskJob` | order.event、rank.update、risk.rules(广播) | 高频下单（10s 秒桶滑动计数）、挂撤刷单（3s 内快撤计数）、异常收益（leave-one-out z-score） | risk.alert → risk-service 限时封禁 |
| `PnlJob` | trade.event(从最早重放)、market.tick(广播) | 每用户现金流 + 持仓 × 最新价，1s 定时器重算 | rank.update → ZSet 实时排名 |

- 阈值在 Nacos `risk-rules.yaml`，risk-service 监听变更写入 compact topic `risk.rules`，Flink 广播状态即时生效
- 排行榜：Flink 实时增量 + rank-service 每 30s 全量校准，两者互为兜底
- RocksDB 状态后端，10s checkpoint；TaskManager 被 kill 后约 20s 从 checkpoint 恢复

## 本地启动
```bash
# 0. 依赖：JDK21、Maven、Docker（Docker Desktop 内存建议 ≥ 10GB，跑 Flink 需 12GB）
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# 1. 中间件
docker compose -f deploy/docker-compose.yml --profile infra --profile mq --profile flink up -d
./deploy/scripts/nacos-import.sh          # 发布 Nacos 配置（含 Sentinel 规则）
./deploy/scripts/rocketmq-topics.sh       # 预建 RocketMQ topic

# 2. 构建 & 启动全部服务（日志在 .run/logs/）
./deploy/scripts/run-local.sh

# 3. 提交 Flink 作业
./deploy/scripts/flink-submit.sh

# 4. 前端
cd frontend && npm install && npx vite    # http://localhost:5173
```

## 验证脚本
| 脚本 | 内容 |
|---|---|
| `deploy/scripts/smoke.sh` | 注册→报名→市价买→幂等→T+1→资金不足→伪造身份→限价挂单/撤单→Redis 与 MySQL 账本一致 |
| `deploy/scripts/risk-demo.sh` | 模拟挂撤刷单脚本，验证 Flink 识别 → 封禁 → 下单被拒 |
| `deploy/scripts/pnl-check.sh` | 对比 Flink 实时盈亏与账户实时总资产 |
| `deploy/scripts/bench.sh` | 撮合引擎 JMH 基准（含 GC profiler） |

| 控制台 | 地址 |
|---|---|
| Nacos | http://localhost:8848/nacos |
| Sentinel | http://localhost:8858 (sentinel/sentinel) |
| RocketMQ Dashboard | http://localhost:8180 |
| Flink Web UI | http://localhost:8090 |
| Gateway | http://localhost:8080 |

## 服务端口
gateway 8080 · user 8081 · market 8082 · trade 8083 · account 8084 · risk 8085 · rank 8086
