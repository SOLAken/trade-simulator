# 部署指南

一共三种部署方式，按需选择：

| 方式 | 适合 | 需要安装 | 命令 |
|---|---|---|---|
| A. 纯 Docker | 体验、演示、部署到一台服务器 | Docker | `make docker-up` |
| B. 本机开发 | 改代码、调试、压测 | Docker + JDK 21 + Maven + Node | `make dev` + `make web` |
| C. Kubernetes（kind） | 体验 StatefulSet、HPA 扩缩容 | B 的全部 + kind + kubectl | `make k8s` |

三种方式的中间件都在 docker compose 里，区别只在 7 个 Java 服务跑在哪里：A 在容器里，B 在宿主机 JVM 上，C 在 kind 集群里。**同一时间只运行一种**，详见文末「切换部署方式」。

---

## 0. 准备

### 硬件
| 场景 | Docker 可用内存 | CPU |
|---|---|---|
| 不含 Flink | ≥ 10GB（实测整套约 6GB） | 4 核以上 |
| 含 Flink（实时风控、实时排行） | ≥ 12GB | 4 核以上 |
| K8s（kind） | ≥ 12GB | 6 核以上 |

磁盘约 10GB，用于镜像、Maven 依赖缓存和数据卷。

### 软件
| 系统 | 安装 |
|---|---|
| macOS | [Docker Desktop](https://www.docker.com/products/docker-desktop/)，在 Settings → Resources 调大内存。方式 B 另外执行 `brew install openjdk@21 maven node jq` |
| Linux | Docker Engine 24+ 和 compose 插件（`docker compose version` 能输出版本即可）。方式 B 另装 JDK 21、Maven 3.9+、Node 18+、jq |
| Windows | Docker Desktop（WSL2 后端），**在 WSL2 的 Ubuntu 终端里**克隆和执行命令。在 `%UserProfile%\.wslconfig` 设置 `memory=12GB` |

没有 `make` 时：Ubuntu/WSL 执行 `sudo apt install make`，macOS 执行 `xcode-select --install`。也可以直接运行下文给出的原始命令。

### 获取代码并自检
```bash
git clone https://github.com/SOLAken/trade-simulator.git
cd trade-simulator
make check                  # 方式 B / C 用：检查 Docker 内存、JDK、Maven、Node、jq 和端口
MODE=docker make check      # 方式 A 用：只检查 Docker 部分
```

---

## A. 纯 Docker 部署

```bash
make docker-up              # 不含 Flink
make docker-up-flink        # 含 Flink 实时风控和实时排行
```

等价的原始命令：
```bash
BROKER_IP=rmq-broker docker compose -f deploy/docker-compose.yml \
  --profile infra --profile mq --profile app up -d --build
# 含 Flink 时再加：--profile flink --profile flink-jobs
```

### 启动时做了什么
1. 启动中间件：MySQL ×2、TimescaleDB、Redis、Nacos、Sentinel、Kafka、RocketMQ。初始化 SQL 只在数据卷第一次创建时执行，包括 2 库 × 4 表分片和默认比赛「常驻公开赛」。
2. 运行两个一次性容器，完成后自动退出：
   - `nacos-init`：把 `deploy/nacos/` 下的业务配置和 Sentinel 规则发布到 Nacos
   - `rmq-init`：预建 RocketMQ topic
3. 在镜像里用 Maven 构建 7 个服务（BuildKit 缓存依赖，第二次构建很快），等初始化完成后启动。
4. 构建前端，由 nginx 托管，并把 `/api`、`/ws` 反向代理到网关。
5. （含 Flink 时）`flink-submit` 把 `PnlJob`、`RiskJob` 提交到 Flink 集群；作业已在运行时会跳过。

首次启动约 5–10 分钟，主要花在下载镜像和 Maven 依赖上。容器全部 Up 之后，服务还需要 30–60 秒注册到 Nacos，可以这样确认：
```bash
curl localhost:8080/actuator/health      # 返回 {"status":"UP"}
make smoke                               # 端到端冒烟测试，最后一行输出「全部通过」
```
然后打开 http://localhost:5173 。

### 日常运维
| 操作 | 命令 |
|---|---|
| 查看状态 | `make docker-ps` |
| 看日志 | `make docker-logs`，只看某个服务：`make docker-logs S=trade-service` |
| 更新代码后重新部署 | `git pull && make docker-up`（只重建有变化的镜像） |
| 重启单个服务 | `docker compose -f deploy/docker-compose.yml --profile app restart trade-service` |
| 停止（保留数据） | `make docker-down` |
| 清空数据重来 | `make docker-down-volumes && make docker-up` |

### 部署到服务器（公网）
纯 Docker 方式可以直接部署到一台 Linux 服务器（建议 4 核 16GB）。上线前务必：
1. `cp .env.example .env`，修改 `JWT_SECRET`（至少 32 字节随机串，可用 `openssl rand -hex 32` 生成）和数据库口令。口令只在数据卷**首次**创建时生效。
2. 防火墙只开放 5173（或在前面再加一层 nginx 配 HTTPS，反代到 5173）。compose 默认把 3306、6379、8848 等中间件端口也映射到了宿主机，公网服务器上要用安全组挡住，或者删掉 `deploy/docker-compose.yml` 里相应的 `ports`。
3. Nacos 默认没开鉴权（`NACOS_AUTH_ENABLE: "false"`），Sentinel 控制台用的是默认账号。这两个控制台都不要暴露到公网。

---

## B. 本机开发

```bash
make dev          # 中间件 → 导入 Nacos → 预建 topic → mvn package → 启动 7 个服务
make web          # 另开一个终端：前端开发服务器 http://localhost:5173（热更新）
```

- 服务日志在 `.run/logs/<服务>.log`，GC 日志在 `.run/logs/<服务>-gc.log`。
- 改完代码后：`./deploy/scripts/run-local.sh` 会重新构建并重启全部服务。不重新构建、只重启某几个：`./deploy/scripts/run-local.sh trade-service account-service`。
- 停止服务：`make stop`。停止中间件：`make infra-down`。
- Flink：执行 `make dev-flink`，或在 `make dev` 之后执行 `WITH_FLINK=1 ./deploy/scripts/infra-up.sh && make flink`。Web UI 在 http://localhost:8090 。
- JDK 查找顺序：`JAVA_HOME` → `/usr/libexec/java_home -v 21+` → Homebrew → `PATH` 中的 java，要求主版本号 ≥ 21。
- 单元测试：`make test`。其中 FundStoreTest 用 Testcontainers 启动 Redis，需要 Docker 在运行。

各服务端口：gateway 8080 · user 8081 · market 8082 · trade 8083 · account 8084 · risk 8085 · rank 8086

---

## C. Kubernetes（kind）

另外需要安装 `kind` 和 `kubectl`（macOS：`brew install kind kubectl`）。

```bash
make k8s          # 中间件 → 建 kind 集群 → 构建并加载镜像 → 部署 → 等待就绪
GW=http://localhost:18080 make smoke
RATE=300 ./deploy/scripts/hpa-demo.sh     # 加压观察 HPA：gateway 2→6，trade-service 2→4
make k8s-down     # 删除集群，切回本机模式
```

- 中间件仍然在 compose 里。kind 节点加入 compose 网络，`k8s-up.sh` 为每个中间件生成不带 selector 的 Service 和对应的 EndpointSlice（`deploy/k8s/infra-endpoints.yaml`，不纳入版本库），Pod 里用与 compose 相同的主机名访问中间件。
- 口令写在 Secret `app-secret` 里，取自 `.env`，没有 `.env` 时用默认值。
- trade-service 是 StatefulSet，Pod 序号就是撮合节点号，重启后按序号认领自己的挂单。

---

## 配置说明

### 环境变量（`.env`）
| 变量 | 默认值 | 说明 |
|---|---|---|
| `MYSQL_PASSWORD` | `root` | 两个 MySQL 分片的 root 口令 |
| `PG_PASSWORD` | `postgres` | TimescaleDB 口令 |
| `JWT_SECRET` | 开发用默认值 | 登录令牌签名密钥，公网部署必须修改 |

### 业务规则（Nacos，改了立即生效）
打开 http://localhost:8848/nacos → 配置管理 → 编辑后发布。也可以直接改 `deploy/nacos/` 下的文件，再执行 `./deploy/scripts/nacos-import.sh`。

| dataId | 配置项 |
|---|---|
| `trade-rules.yaml` | 佣金（万三，最低 5 元）、印花税 0.1%、涨跌停 ±10%、市价滑点；交易日 30 分钟 + 休市 60 秒（交易日长度改了要重启才生效） |
| `risk-rules.yaml` | 10 秒下单上限 20、快撤判定 3 秒、30 秒快撤上限 8、异常收益 z 值 4、封禁 10 分钟（需要 Flink 在运行） |
| Sentinel 规则（`SENTINEL_GROUP`） | 网关下单路由 200 QPS、单用户每秒 20 次；placeOrder 热点限流 |

---

## 切换部署方式

三种方式共用同一套端口，RocketMQ broker 的宣告地址也不一样（A、C 用容器主机名 `rmq-broker`，B 用 `127.0.0.1`），所以切换前要先停掉当前方式：

| 从 → 到 | 先执行 |
|---|---|
| A → B | `make docker-down`，再执行 `make dev`（会把 broker 切回 127.0.0.1） |
| B → A | 不用额外操作，`make docker-up` 会先停掉宿主机上的服务 |
| C → B 或 A | `make k8s-down` |

---

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 登录提示 Internal Server Error | 网关没在运行。`curl localhost:8080/actuator/health` 检查；方式 A 用 `make docker-ps` 看容器状态，方式 B 执行 `make dev` |
| 刚启动时接口返回 503 | 服务还没注册到 Nacos，等 30–60 秒 |
| 下单后一直不成交或不结算，日志里有 RocketMQ 连接超时 | broker 宣告地址和当前部署方式不匹配，见「切换部署方式」 |
| 容器反复重启、`docker inspect` 显示 OOMKilled | Docker 内存不够，调大到 10GB 以上 |
| 首笔撤单要十几秒才解冻 | topic 没有预建，执行 `./deploy/scripts/rocketmq-topics.sh` |
| 端口被占用 | `make check` 会列出被占用的端口。用 `lsof -i :8080` 找到对应进程，或者修改 compose 里的端口映射 |
| Apple Silicon 上 rmq-dashboard 启动慢 | 官方只提供 amd64 镜像，通过 Rosetta 运行，不影响业务 |
| 排行榜不实时、刷单不封禁 | 没启动 Flink。排行榜仍有 30 秒一次的全量校准；需要实时排行和风控时用 `make docker-up-flink` |
| 修改了 `.env` 里的口令后连不上数据库 | 数据卷保留了旧口令。`make docker-down-volumes` 清空数据后重新启动 |

## 卸载

```bash
make docker-down-volumes                  # 删除容器和数据卷
make k8s-down                             # 如果建过 kind 集群
docker image rm $(docker images 'tradesim/*' -q)
docker builder prune                      # 可选：清理构建缓存（Maven 依赖缓存也在里面）
```
