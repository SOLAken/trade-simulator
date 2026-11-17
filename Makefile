# 统一入口：make help 查看全部命令
SHELL := /bin/bash
COMPOSE := docker compose -f deploy/docker-compose.yml
ALL_PROFILES := --profile infra --profile mq --profile flink --profile app --profile flink-jobs
APP_PROFILES := --profile infra --profile mq --profile app

.DEFAULT_GOAL := help
.PHONY: help check docker-up docker-up-flink docker-down docker-down-volumes docker-ps docker-logs \
        dev dev-flink web stop infra-down test smoke flink bench k8s k8s-down

help: ## 显示帮助
	@awk 'BEGIN {FS = ":.*## "} /^[a-z0-9-]+:.*## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

check: ## 环境自检（本机开发所需）
	@./deploy/scripts/check-env.sh

# ---------------- 方式 A：纯 Docker（只需要 Docker） ----------------
docker-up: ## 纯 Docker 一键启动全栈（首次构建约 5–10 分钟）→ http://localhost:5173
	@MODE=docker ./deploy/scripts/check-env.sh
	@./deploy/scripts/run-local.sh stop >/dev/null 2>&1 || true
	BROKER_IP=rmq-broker $(COMPOSE) $(APP_PROFILES) up -d --build --wait --wait-timeout 600
	@echo "✓ 已启动：http://localhost:5173"

docker-up-flink: ## 纯 Docker 全栈 + Flink 实时风控/收益（内存建议 ≥ 12GB）
	@NEED_GB=12 MODE=docker ./deploy/scripts/check-env.sh
	@./deploy/scripts/run-local.sh stop >/dev/null 2>&1 || true
	BROKER_IP=rmq-broker $(COMPOSE) $(APP_PROFILES) --profile flink --profile flink-jobs up -d --build
	@echo "✓ 已启动：http://localhost:5173  Flink UI: http://localhost:8090"

docker-ps: ## 查看容器状态
	$(COMPOSE) $(ALL_PROFILES) ps

docker-logs: ## 跟踪应用日志（S=服务名 只看某个，如 make docker-logs S=trade-service）
	$(COMPOSE) $(ALL_PROFILES) logs -f --tail=100 $(S)

docker-down: ## 停止并删除全部容器（保留数据卷）
	$(COMPOSE) $(ALL_PROFILES) down

docker-down-volumes: ## 停止并删除全部容器和数据卷（清空数据）
	$(COMPOSE) $(ALL_PROFILES) down -v

# ---------------- 方式 B：本机开发（中间件在 Docker，服务在宿主机 JVM） ----------------
dev: ## 启动中间件并初始化 → 构建并启动 7 个服务（日志在 .run/logs/）
	@./deploy/scripts/check-env.sh
	./deploy/scripts/infra-up.sh
	./deploy/scripts/run-local.sh

dev-flink: ## 同 dev，并启动 Flink 集群和作业
	WITH_FLINK=1 ./deploy/scripts/infra-up.sh
	./deploy/scripts/run-local.sh
	./deploy/scripts/flink-submit.sh

web: ## 启动前端开发服务器 → http://localhost:5173
	cd frontend && ([ -d node_modules ] || npm install) && npx vite

stop: ## 停止宿主机上的 7 个服务
	./deploy/scripts/run-local.sh stop

infra-down: ## 停止中间件（保留数据卷）
	$(COMPOSE) $(ALL_PROFILES) stop

flink: ## 构建并提交 Flink 作业（本机开发模式）
	./deploy/scripts/flink-submit.sh

# ---------------- 测试与演示 ----------------
test: ## 单元测试（含 Testcontainers，需 Docker）
	@source deploy/scripts/lib.sh && mvn -B test

smoke: ## 端到端冒烟测试（GW=网关地址，默认 http://localhost:8080）
	./deploy/scripts/smoke.sh

bench: ## 撮合引擎 JMH 基准
	./deploy/scripts/bench.sh

# ---------------- 方式 C：Kubernetes（kind） ----------------
k8s: ## 部署到本地 kind 集群（中间件仍在 compose）→ 网关 http://localhost:18080
	./deploy/scripts/infra-up.sh
	./deploy/scripts/k8s-up.sh

k8s-down: ## 删除 kind 集群并切回本机模式
	./deploy/scripts/k8s-up.sh down
