#!/usr/bin/env bash
# 一键部署到本地 kind 集群（中间件仍在 docker compose 中）
#   ./deploy/scripts/k8s-up.sh          创建集群 → 构建并加载镜像 → 部署 → 等待就绪
#   ./deploy/scripts/k8s-up.sh down     删除集群，并把 RocketMQ broker 切回宿主机模式
#
# 网络打通方式：
#   1) kind 节点容器加入 compose 网络 tradesim_default，Pod 出网经节点 SNAT 即可访问中间件容器 IP
#   2) 为每个中间件生成「无 selector 的 Service + EndpointSlice」，指向容器 IP。
#      Pod 内用与 compose 相同的主机名（kafka、rmq-broker…）访问，Kafka/RocketMQ 对外宣告的地址也能被解析
#   3) RocketMQ broker 宣告地址切为 rmq-broker（宿主机模式下是 127.0.0.1），见 docker-compose.yml 的 BROKER_IP
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
CLUSTER=tradesim
NET=tradesim_default
COMPOSE="docker compose -f deploy/docker-compose.yml --profile infra --profile mq --profile flink"

if [ "${1:-}" = down ]; then
  kind delete cluster --name $CLUSTER
  BROKER_IP=127.0.0.1 $COMPOSE up -d rmq-broker
  exit 0
fi

echo "▶ 停止宿主机上的服务（避免与集群内实例同时注册到 Nacos）"
./deploy/scripts/run-local.sh stop >/dev/null

echo "▶ RocketMQ broker 切换为容器网络模式"
BROKER_IP=rmq-broker $COMPOSE up -d rmq-broker

echo "▶ kind 集群"
kind get clusters | grep -qx $CLUSTER || kind create cluster --config deploy/k8s/kind-config.yaml
docker network connect $NET ${CLUSTER}-control-plane 2>/dev/null || true
kubectl config use-context kind-$CLUSTER >/dev/null

echo "▶ metrics-server（HPA 依赖）"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml >/dev/null
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null 2>&1 || true

echo "▶ 生成中间件 Service/EndpointSlice"
out=deploy/k8s/infra-endpoints.yaml
: > $out
for spec in "mysql-0:mysql-0:3306" "mysql-1:mysql-1:3306" "timescale:timescale:5432" "redis:redis:6379" \
            "nacos:nacos:8848,9848" "kafka:kafka:29092" "rmq-namesrv:rmq-namesrv:9876" \
            "rmq-broker:rmq-broker:10909,10911,10912" "sentinel-dashboard:sentinel-dashboard:8858"; do
  IFS=: read -r name container ports <<< "$spec"
  ip=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "tradesim-$container-1")
  {
    echo "---"
    echo "apiVersion: v1"
    echo "kind: Service"
    echo "metadata: { name: $name, namespace: tradesim }"
    echo "spec:"
    echo "  ports:"
    for p in ${ports//,/ }; do echo "    - { name: p$p, port: $p, targetPort: $p }"; done
    echo "---"
    echo "apiVersion: discovery.k8s.io/v1"
    echo "kind: EndpointSlice"
    echo "metadata: { name: $name, namespace: tradesim, labels: { kubernetes.io/service-name: $name } }"
    echo "addressType: IPv4"
    echo "endpoints: [{ addresses: [\"$ip\"] }]"
    echo "ports:"
    for p in ${ports//,/ }; do echo "  - { name: p$p, port: $p, protocol: TCP }"; done
  } >> $out
  printf '  %-20s %s\n' "$name" "$ip"
done

echo "▶ 构建镜像并加载进 kind"
./deploy/scripts/build-images.sh
for m in gateway user-service market-service trade-service account-service risk-service rank-service; do
  kind load docker-image tradesim/$m:latest --name $CLUSTER >/dev/null
done

echo "▶ 部署"
kubectl apply -k deploy/k8s
# 镜像 tag 是 latest：重新部署时强制滚动，拿到刚加载的新镜像
kubectl -n tradesim rollout restart deployment >/dev/null
kubectl -n tradesim rollout restart statefulset >/dev/null
kubectl -n tradesim rollout status statefulset/trade-service --timeout=300s
for d in gateway user-service market-service account-service risk-service rank-service; do
  kubectl -n tradesim rollout status deployment/$d --timeout=300s
done
kubectl -n tradesim get pods -o wide
echo "网关：http://localhost:18080"
