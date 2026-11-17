# Trade Simulator：股票交易模拟器联机游戏

[![CI](https://github.com/SOLAken/trade-simulator/actions/workflows/ci.yml/badge.svg)](https://github.com/SOLAken/trade-simulator/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange) ![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0-green) ![License](https://img.shields.io/badge/license-MIT-blue)

这是一个房间制的模拟炒股比赛。行情由程序生成（GBM 加跳变事件），支持市价、限价和止损单，有 T+1 和涨跌停规则，带实时排行榜和实时风控。

后端拆成 6 个微服务和 1 个网关，重点实现了三条链路：

- **撮合引擎**：股票按哈希分片，每片一个 Disruptor 单线程无锁撮合，按价格时间优先，支持涨跌停封板。JMH 实测 4 分片约 446 万事件/秒。
- **资金一致性**：下单前用 Redis Lua 原子冻结资金，再经 RocketMQ 事务消息结算。账户服务用可交换的增量账本，消息乱序也不会出错，最后定时两遍对账兜底。
- **实时风控与收益**：Flink 识别挂撤刷单、计算异常收益的 z-score，并实时算出收益率排行。风控规则通过 Nacos → Kafka 广播热更新，作业不用重启。

```
                    ┌──────────── Nacos（注册 + 配置）────────────┐
Vue3 ──► Gateway ──►│ user   market   trade   account   risk   rank │
 (JWT/Sentinel/WS)  └──────────────────────────────────────────────┘
                         │        │        │         │       │
        TimescaleDB ◄────┘   Kafka market.tick ──► trade(撮合) / Flink / WS
        MySQL×2(分片) ◄─────────── trade ──RocketMQ 事务消息──► account
        Redis(热数据/幂等/ZSet) ◄── 全部
        Flink：order.event/trade.event ──► risk.alert / rank.update
```

## 快速开始

### 方式 A：纯 Docker 一键部署（推荐，只需要 Docker）

```bash
git clone https://github.com/SOLAken/trade-simulator.git && cd trade-simulator
make docker-up            # 首次需要在镜像里构建，约 5–10 分钟
```

启动后打开 http://localhost:5173 ，注册账号、报名比赛，就可以开始交易。

| 命令 | 作用 |
|---|---|
| `make docker-up-flink` | 同时启动 Flink 集群并提交风控和收益作业（Docker 内存建议 ≥ 12GB） |
| `make docker-ps` / `make docker-logs S=trade-service` | 查看容器状态和日志 |
| `make docker-down` | 停止服务，保留数据 |
| `make docker-down-volumes` | 停止服务并清空数据 |

没有 `make` 时，可以直接运行：
```bash
BROKER_IP=rmq-broker docker compose -f deploy/docker-compose.yml --profile infra --profile mq --profile app up -d --build
```

### 方式 B：本机开发（中间件跑在 Docker 里，服务跑在宿主机 JVM 上）

需要 JDK 21+、Maven 3.9+、Node 18+ 和 jq。脚本会自动查找 JDK（依次查 `JAVA_HOME`、`/usr/libexec/java_home`、Homebrew 和 `PATH`）。

```bash
make check     # 环境自检
make dev       # 启动中间件 → 导入 Nacos 配置和 Sentinel 规则 → 预建 RocketMQ topic → 构建并启动 7 个服务
make web       # 前端开发服务器 http://localhost:5173
make smoke     # 端到端冒烟测试
make flink     # （可选）提交 Flink 作业，需要先执行 WITH_FLINK=1 ./deploy/scripts/infra-up.sh
make stop      # 停止服务
```

服务日志在 `.run/logs/` 下。只重启某几个服务：`./deploy/scripts/run-local.sh trade-service account-service`。

### 方式 C：Kubernetes（kind）

```bash
make k8s                                  # 建集群 → 构建并加载镜像 → 部署 → 等待就绪，网关地址 http://localhost:18080
GW=http://localhost:18080 make smoke
RATE=300 ./deploy/scripts/hpa-demo.sh     # 加压观察 HPA 扩容
make k8s-down                             # 删除集群，RocketMQ broker 切回本机模式
```

中间件仍然跑在 compose 里。kind 节点会加入 compose 网络，每个中间件生成一个不带 selector 的 Service 和对应的 EndpointSlice，所以 Pod 里可以用和 compose 相同的主机名访问中间件。trade-service 用 StatefulSet 部署（Pod 序号就是撮合节点号），gateway 和 trade-service 配了 HPA。口令放在 Secret `app-secret` 里。

## 环境要求

| 项 | 要求 |
|---|---|
| Docker | Docker Desktop / Docker Engine 24+，需要 compose v2 |
| Docker 内存 | 方式 A 至少 10GB；加 Flink 至少 12GB |
| 架构 | amd64 / arm64 均可（只有 RocketMQ Dashboard 是 amd64 镜像，Apple Silicon 上通过 Rosetta 运行） |

## 配置

口令和密钥都从环境变量读取，不设置时使用开发默认值。需要修改时：

```bash
cp .env.example .env    # 修改 MYSQL_PASSWORD / PG_PASSWORD / JWT_SECRET
```

compose、本机启动脚本和 `k8s-up.sh` 会读取同一个 `.env`。**公网部署前必须修改 `JWT_SECRET`。** 数据库口令只在数据卷第一次初始化时生效，改口令要先执行 `make docker-down-volumes`。

业务规则（费率、涨跌停幅度、交易日长度）在 `deploy/nacos/trade-rules.yaml`，风控阈值在 `deploy/nacos/risk-rules.yaml`。在 Nacos 控制台修改后立即生效，不需要重启服务。

## 端口与控制台

| 地址 | 说明 |
|---|---|
| http://localhost:5173 | 前端 |
| http://localhost:8080 | 网关（服务端口：user 8081 · market 8082 · trade 8083 · account 8084 · risk 8085 · rank 8086） |
| http://localhost:8848/nacos | Nacos |
| http://localhost:8858 | Sentinel（sentinel / sentinel） |
| http://localhost:8180 | RocketMQ Dashboard |
| http://localhost:8090 | Flink Web UI |

## 常见问题

- **登录时报 Internal Server Error**：说明后端没在运行，前端代理连不上网关。用 `curl localhost:8080/actuator/health` 检查，正常时返回 `{"status":"UP"}`。方式 B 下执行 `make dev`。
- **执行过 `make k8s` 之后本机模式不工作**：先执行 `make k8s-down`。K8s 模式会把 RocketMQ broker 的宣告地址改成容器主机名，宿主机上的服务连不上。
- **容器反复重启或出现 OOMKilled**：调大 Docker Desktop 的内存（Settings → Resources）。
- **首笔撤单要等十几秒才解冻**：RocketMQ topic 没有预建。执行 `./deploy/scripts/rocketmq-topics.sh`（`make dev` 和方式 A 会自动执行）。

## 目录结构

```
common/            公共库：基因雪花 ID、分片算法、Redis Lua 资金脚本、MarketClock、交易规则
gateway/           Spring Cloud Gateway：JWT 鉴权、Sentinel 限流、WebSocket 路由
user-service/      注册登录、比赛报名
market-service/    行情模拟器（GBM + 跳变）、K 线、回放
trade-service/     下单、撮合引擎（Disruptor）、RocketMQ 事务消息
account-service/   MySQL 账本、对账
risk-service/      风控规则下发、告警处置
rank-service/      排行榜、收益曲线
flink-jobs/        RiskJob（刷单 / 异常收益）、PnlJob（实时收益）
frontend/          Vue3 + Vite + ECharts + Pinia
deploy/
  docker-compose.yml   中间件与应用（profiles：infra / mq / flink / app / flink-jobs）
  docker/              纯 Docker 部署用的镜像（镜像内构建）
  Dockerfile           K8s 镜像（宿主机构建后分层打包）
  k8s/                 kind 集群清单（StatefulSet、HPA、探针）
  nacos/               Nacos 配置与 Sentinel 规则
  mysql/ timescale/    初始化 SQL（2 库 × 4 表分片）
  scripts/             启动、初始化、演示与压测脚本
  loadtest/            k6 开盘洪峰压测
docs/              架构设计与性能报告
```

## 技术栈

| 组件 | 用途 |
|---|---|
| Spring Boot 3.2 / Spring Cloud 2023 / Spring Cloud Alibaba | 微服务、OpenFeign、虚拟线程 |
| Nacos | 服务注册；`trade-rules.yaml` / `risk-rules.yaml` 热更新；Sentinel 规则持久化 |
| Sentinel | 开盘集中下单时的网关限流，按用户热点参数防刷 |
| Redis | 最新行情、资金和持仓热数据（Lua 原子冻结）、幂等、ZSet 排行 |
| Kafka | `market.tick`、`order.event`、`trade.event` 事件流 |
| RocketMQ | 事务消息（冻结 → 成交 → 结算）、定时消息（收盘撤单） |
| MySQL + ShardingSphere-JDBC | 订单、成交、流水按 user_id 分 2 库 × 4 表，用基因法生成 ID |
| TimescaleDB | tick hypertable、连续聚合 K 线、回放、收益曲线 |
| Flink | 刷单识别、异常收益、实时收益率（RocksDB 状态后端，checkpoint 恢复） |
| Disruptor | 撮合引擎按股票分片，每片单线程无锁 |
| Docker / K8s | compose 一键部署、kind、StatefulSet、HPA |

## 验证与演示脚本

| 脚本 | 内容 |
|---|---|
| `deploy/scripts/smoke.sh` | 注册 → 报名 → 市价买 → 幂等 → T+1 → 资金不足 → 伪造身份 → 限价挂单 / 撤单 → Redis 与 MySQL 账本一致 |
| `deploy/scripts/risk-demo.sh` | 模拟挂撤刷单脚本，验证 Flink 识别 → 封禁 → 下单被拒 |
| `deploy/scripts/pnl-check.sh` | 对比 Flink 实时盈亏和账户实时总资产 |
| `deploy/scripts/bench.sh` | 撮合引擎 JMH 基准（含 GC profiler） |
| `deploy/loadtest/open-burst.js` | k6 开盘洪峰压测（100 → 2000 单/秒） |
| `deploy/scripts/gc-compare.sh` | 同一压测下 G1 和分代 ZGC 的 STW 与延迟对比 |
| `deploy/scripts/trade-timers.sh` | 下单各阶段耗时（Micrometer） |
| `curl localhost:8084/internal/account/reconcile?contestId=1` | Redis 与 MySQL 账本两遍对账 |

## 文档

- [架构设计](docs/ARCHITECTURE.md)：三条核心链路的设计和取舍
- [性能与调优](docs/PERFORMANCE.md)：JMH、开盘洪峰压测、问题定位过程、GC 对比、容器内存、HPA

## License

[MIT](LICENSE)
