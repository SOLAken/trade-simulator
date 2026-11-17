# 性能与调优报告

环境：10 核 / 24GB 笔记本，中间件在 Docker Desktop（分配 15.6GB），7 个服务在宿主机 JVM（JDK 21）。
所有数据可复现：`deploy/scripts/bench.sh`、`deploy/loadtest/open-burst.js`、`deploy/scripts/gc-compare.sh`。

## 1. 撮合引擎（JMH）

| 基准 | G1 | 分代 ZGC |
|---|---|---|
| 单订单簿单线程（70% 下单 / 20% 撤单 / 10% 行情） | 2.66M ops/s，203 B/op | 2.40M ops/s，252 B/op |
| 4 分片引擎，8 生产线程（80% 下单 / 20% 行情） | **4.20M events/s**，416 B/op | **4.46M events/s**，528 B/op |

- 分片单线程、彼此独立，4 分片吞吐约为单簿的 1.7 倍（受限于 8 个生产线程与笔记本核数）。
- 基准自身踩过的坑：最初的引擎基准只下单不发 tick，订单永不成交、簿无限膨胀，误差 ±400%，测到的是堆增长。改为混入行情后误差降到 ±5%。

## 2. 开盘洪峰压测（k6）

场景：500 个用户，下单速率 100/s → **3 秒内升到 2000/s 并保持 20 秒** → 回落；60% 限价单、40% 市价单。

### 第一轮：服务被打穿

| 指标 | 结果 |
|---|---|
| 放行吞吐 | 138 单/s |
| 放行延迟 | avg 6.66s，P99 14.7s |
| 被限流 (429) | 1766 |
| 5xx | 34（trade-service 的 Tomcat 接收队列溢出，连接被拒） |

**根因：限流阈值（500 QPS）高于真实容量**，Sentinel 根本没触发，请求全部堆积在服务内部排队。

### 定位过程

1. **虚拟线程钉住（pinning）**。`-Djdk.tracePinnedThreads=short` 显示 MySQL Connector/J 8.3 在 `synchronized` 内做 Socket 读写（`ReadAheadInputStream.read`、`setAutoCommit`、`prepareStatement`）。虚拟线程阻塞在这里会钉死载体线程，而载体线程数 = CPU 核数（10），DB IO 实际并发被压到 10。
   → 升级 Connector/J 9.1（改用 `ReentrantLock`），复测钉住栈只剩 Kafka 后台线程。**这是并发模型上的真实缺陷，但不是本次瓶颈**：修复后延迟没有改善。
2. **分阶段计时**（Micrometer，`deploy/scripts/trade-timers.sh`）：

   | 阶段（200 单/s） | 平均 |
   |---|---|
   | 冻结 · RocketMQ 半消息 | ~2ms |
   | 冻结 · 本地事务（插订单 + Lua + 提交） | **10.5–12.7ms** |
   | 结算 · 本地事务 | 7–11ms |
   | 等引擎（限价单，纯内存路径） | **11–24ms** |

   瓶颈在 MySQL 本地事务；但「等引擎」这条纯内存路径（JMH 中是微秒级）也要十几毫秒，说明不是某个组件慢，而是整机调度延迟。
3. **假设：提交 fsync 慢** → 临时设 `innodb_flush_log_at_trx_commit=2`，本地事务只从 12.7ms 降到 10.5ms。**否定**，已恢复为 1（资金账本不拿持久性换速度）。
4. **假设：网络往返过多** → 开启服务端预编译缓存 + `useLocalSessionState`。无可测改善（保留，属于正确配置）。
5. **CPU 画像**：Docker Desktop 虚拟机 288%、`com.docker.backend`（宿主机↔容器端口转发代理）49%，而容器内中间件合计只占约 90%。
   **结论：本机环境的主要开销在 Docker Desktop 的虚拟化与端口转发**——每笔订单对 MySQL / Redis / RocketMQ 约 10 次往返，每次都穿过用户态代理。服务与中间件在同一容器网络（compose app / K8s）可绕开这一层。
   本机实测可持续容量约 **200 单/s**。

### 两处修正

1. **限流阈值 = 实测容量**：网关 trade 路由 500 → 200 QPS（Nacos 推送，不重启）。
2. **下单接口不长时间等成交**：等待引擎结果的上限从 2s 降到 300ms，超时返回「已报」，成交靠前端刷新。第二轮的 P99 = 2.01s，正是卡在旧的 2s 上限。

### 修正后（分代 ZGC）

| 指标 | 第一轮 | 修正后 |
|---|---|---|
| 5xx | 34 | **0** |
| 被限流 (429) | 1766 | **43069**（中位耗时 176µs，快速失败） |
| 放行延迟 avg / P95 / max | 6.66s / 11.9s / 22.9s | **165ms / 384ms / 581ms** |
| 压测后对账 | — | **1813 个账户、10892 条持仓，零差异** |

洪峰被 Sentinel 削平为快速 429，放行流量的延迟稳定在容量范围内，资金零差错——限流的目的是保护，而不是追求吞吐。

## 3. GC 对比（trade-service，同一压测场景）

统一口径：`-Xlog:safepoint` 统计所有 GC 相关的 STW（G1 的 Young/Remark，ZGC 的 Mark Start/End、Relocate Start）。

| | 分代 ZGC | G1（MaxGCPauseMillis=50） |
|---|---|---|
| STW 次数 / **总停顿** | 152 / **7.2ms** | 93 / **252.3ms** |
| STW P99 / **最大** | 0.19ms / **0.24ms** | 21ms / **21ms**（Remark） |
| 放行延迟 avg / P95 | 165ms / 384ms | 200ms / 447ms |
| 每操作分配（JMH 引擎） | 528 B | 416 B |

- ZGC 停顿与堆大小无关，始终亚毫秒；G1 单次 Remark 达 21ms，对撮合这类尾延迟敏感的路径不友好。
- 代价：ZGC 每操作分配多约 25%，并发标记/重定位占用后台 CPU。
- **选择分代 ZGC**（镜像默认）。注意：两组各跑一次、先后进行，延迟差异含机器噪声；STW 的数量级差异是确定的。

## 4. 容器内存（K8s 部署实测）

首次部署全部 Pod OOMKilled（640Mi / 768Mi 容器，堆占 70% / 55%）。在 Pod 内看 cgroup：

| 项 | 用量 | 来源 |
|---|---|---|
| shmem | 314Mi | ZGC 用 memfd 共享内存承载堆，计入 cgroup |
| anon | 417Mi | 元空间、代码缓存、87 个线程栈、glibc 每线程 malloc arena、Netty 直接内存 |

修正：
- 堆外显式封顶：`MALLOC_ARENA_MAX=2`、`MaxDirectMemorySize=64m`、`ReservedCodeCacheSize=96m`、`MaxMetaspaceSize=192m`、`-Xss512k`
- 按服务选收集器：无状态服务 G1（小堆更省），撮合服务保留分代 ZGC（1Gi 容器、堆 40%）

| 结果 | 内存 |
|---|---|
| 6 个 G1 服务 | 269–313Mi（此前 640Mi 都放不下） |
| trade-service（ZGC） | 689–736Mi / 1Gi |

## 5. K8s 弹性扩容（kind，单节点）

`RATE=300 DURATION=180s ./deploy/scripts/hpa-demo.sh`：

| 项 | 结果 |
|---|---|
| HPA | gateway 2 → **6**、trade-service 2 → **4**（均到上限），施压约 20 秒内完成扩容 |
| 新增撮合节点 | `trade-service-2/3`，StatefulSet 序号即节点号，雪花 ID 不冲突 |
| 吞吐 / 延迟 | 持续 **290 单/秒**，P95 488ms；最大 12.9s 出现在新 Pod 冷启动阶段 |
| 压测后对账（集群内） | **3114 个账户、17075 条持仓，零差异** |

注意：Sentinel 网关规则是单实例生效，6 个网关副本时总阈值随副本数放大；严格的全局限流需要 Sentinel 集群流控（Token Server），列为后续改进。

## 6. 其他运行时数据

| 项 | 数据 |
|---|---|
| Flink 实时盈亏 vs 账户实时总资产 | 差值均为持仓股数的整数倍（取价时刻相差数百毫秒），公式由单测覆盖 |
| Flink 故障恢复 | kill TaskManager 后约 21 秒从 checkpoint #47 恢复 |
| 刷单识别时延 | 第 9 次快撤后的下一次下单即被拒（< 1 秒） |
| 镜像分层 | 业务代码层 430KB，依赖层不随代码变动 |
