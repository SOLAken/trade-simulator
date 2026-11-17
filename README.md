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

## 本地启动
```bash
# 0. 依赖：JDK21、Maven、Docker（Docker Desktop 内存建议 ≥ 10GB，跑 Flink 需 12GB）
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# 1. 中间件
docker compose -f deploy/docker-compose.yml --profile infra --profile mq up -d
./deploy/scripts/nacos-import.sh          # 发布 Nacos 配置

# 2. 构建 & 启动服务
mvn -q package
java -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar   # 其余服务同理
```

| 控制台 | 地址 |
|---|---|
| Nacos | http://localhost:8848/nacos |
| Sentinel | http://localhost:8858 (sentinel/sentinel) |
| RocketMQ Dashboard | http://localhost:8180 |
| Gateway | http://localhost:8080 |

## 服务端口
gateway 8080 · user 8081 · market 8082 · trade 8083 · account 8084 · risk 8085 · rank 8086
