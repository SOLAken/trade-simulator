# 架构设计

## 1. 业务模型

- **比赛**：房间制，报名即开户（初始资金按比赛设定），按总资产排名。
- **加速市场**：一个交易日 = 30 分钟真实时间 + 60 秒休市。所有服务用同一个 `anchor + 日长` 推算交易日（`MarketClock`），无需互相通信即可对齐。
- **对手方是市场**：玩家单与模拟行情撮合，而不是玩家之间互相撮合。小房间没有流动性问题，每个人的成交只取决于行情和自己的委托。
- **A 股规则**：涨跌停、T+1、100 股整数倍买入、佣金（最低 5 元）+ 卖出印花税。

## 2. 服务划分

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

| 服务 | 职责 | 关键点 |
|---|---|---|
| gateway | 统一入口 | 删除外部传入的 `X-User-Id` 后再按 JWT 写入；Sentinel 路由 QPS + 按令牌热点限流 |
| user | 注册登录、比赛 | 报名经 OpenFeign 调 account 内部接口开户（`/internal/**` 不经网关） |
| market | 行情生成、K 线、回放 | GBM + 跳变；Redis 租约选主；TimescaleDB 连续聚合 |
| trade | 下单、撮合、结算 | Disruptor 分片撮合；RocketMQ 事务消息；StatefulSet 稳定节点号 |
| account | MySQL 账本、对账 | 增量记账可交换，乱序收敛；双遍对账 |
| risk | 规则下发、告警处置 | Nacos → Kafka compact topic → Flink 广播状态 |
| rank | 排行榜、收益曲线 | Flink 实时增量 + 30s 全量校准 |

## 3. 核心链路一：撮合引擎

```
HTTP 线程 ─┐                         ┌─ OrderBook(SIM001)
Kafka 线程 ─┼─► RingBuffer[shard] ─► 单线程 ┼─ OrderBook(SIM005) ─► 动作 ─► SettlementLanes(按 orderId 哈希)
           │   多生产者 CAS 发布         └─ …                              └─► MySQL + RocketMQ + Redis
```

- **单写者**：symbol 哈希到 N 个分片，每个分片一个 Disruptor + 一个消费线程，订单簿只被它修改——无锁、缓存友好、同一 symbol 的下单/撤单/行情天然全序。
- **引擎线程零 IO**：只产出 `Fill`/`Cancel` 动作，落库交给结算通道。通道按 orderId 哈希到固定单线程，同一订单的「成交 → 成交 → 撤单」严格有序（撤单退回金额依赖之前成交已落库），不同订单并行。
- **流动性**：每个 tick 的成交量增量即可成交股数，价格优先、时间优先分配；大单自然拆成多笔部分成交。
- **规则**：限价单穿价按最新价成交（价格改善归玩家）；涨停封板买单只能排队；市价单 IOC；止损单触发后转市价。
- **多实例**：行情广播给每个实例，每个实例只撮合自己受理的订单（订单记录 `node`）。重启后按节点号从 MySQL 认领挂单重建订单簿；K8s 中用 StatefulSet 序号保证节点号稳定。
- **过期**：收盘撤单以 RocketMQ 5 定时消息为主、引擎跨日自检为兜底，账本层 CAS 保证重复撤销无副作用。

## 4. 核心链路二：资金一致性

| 动作 | Redis（热数据） | MySQL 订单 | RocketMQ → account |
|---|---|---|---|
| 下单冻结 | Lua 冻结，**提交前**（校验型：不足要拒单） | 插入订单 | FREEZE |
| 成交结算 | Lua 结算，**提交后**（结果型，幂等重试） | CAS 更新订单 + 写成交 | SETTLE |
| 撤单释放 | Lua 释放，**提交后** | CAS 关闭订单 | RELEASE |

- **事务消息**：半消息 → 本地事务 → 提交；Broker 收不到结果时回查，以本地库是否落下对应记录为准。
- **金额在发送前确定**：事务消息体不可变，所以退回金额由订单行（冻结 − 已用）推出，Lua 只执行显式增量。用 `filled_qty` 做 CAS 版本号，并发撤单/成交只有一方成功，另一方重算重试。
- **增量账本可交换**：account 按消息直接加减、不校验余额，消息乱序也收敛到同一终态，因此不需要顺序消息。幂等靠流水表唯一键 `(user_id, biz_id, type)`。持仓成本（移动加权，与顺序相关）只在 Redis 维护。
- **T+1 惰性解锁**：持仓记录最近交易日，跨日后首次访问把 pending 转为可卖，无需日终批处理。
- **幂等两道闸**：Redis `SETNX`（挡并发重复提交）+ 唯一键 `(user_id, client_key)`（最终防线）。
- **对账**：Redis 与 MySQL 两遍比对（间隔 3s，差异值不变才判定），压测后 1813 个账户、10892 条持仓零差异。

## 5. 核心链路三：实时风控与收益（Flink）

- **高频下单**：每用户「每秒一个桶」的 MapState 做 10 秒滑动计数——比 Flink 滑动窗口省状态（窗口会把事件复制到 10/步长 个窗口），且阈值能随广播规则变化。
- **挂撤刷单**：限价单在 3 秒内被撤记为一次「快撤」，30 秒内超过阈值告警并限时封禁。
- **异常收益**：按比赛 keyBy，用 Σx、Σx² 增量维护均值方差（O(1)），**leave-one-out** z-score（把本人算进样本时 n 人的 z 上限只有 (n−1)/√n）；标准差设下限防开赛初期误报。
- **实时收益**：`trade.event` 从最早位点重放重建持仓，行情走广播状态；不在每个 tick 上遍历用户，而是每个有持仓的用户 1 秒一次定时器重算。
- **规则热更新**：Nacos `risk-rules.yaml` → risk-service 监听 `EnvironmentChangeEvent` → compact topic `risk.rules` → Flink 广播状态，作业不重启。
- **容错**：RocksDB 状态后端，10 秒 checkpoint；kill TaskManager 后约 20 秒从 checkpoint 恢复。

## 6. 分库分表

- 2 库 × 4 表，`slot = key % 8`，库 = slot / 4，表 = slot % 4。
- **基因法 ID**：雪花 ID 低 6 位嵌入 `user_id` 低 6 位，`order_id % 8 == user_id % 8`——按用户查列表、按订单号查详情都能精确路由，不广播。64 是 8 的倍数，未来扩到 64 分片只迁数据、不改 ID。
- 用户、比赛等全局表放 `tradesim_global`（不分片）。

## 7. 限流与配置

- **Sentinel**：网关按路由 QPS（阈值来自压测容量）+ 按 Authorization 头热点限流；trade-service `@SentinelResource` 按 userId 每秒 5 单。规则持久化在 Nacos（`SENTINEL_GROUP`）。
- **Nacos 热更新**：`TradeRules` 用 `@ConfigurationProperties` 绑定，Spring Cloud 的 rebinder 在配置推送后重新绑定——费率、涨跌停、滑点下一笔即生效。日长、锚点会改变交易日序号，只在重启时生效（代码中惰性构造时钟）。

## 8. 部署

- **本地开发**：中间件 docker compose（profile：infra / mq / flink），服务在宿主机 `run-local.sh`。
- **镜像**：Spring Boot 分层 jar，业务代码层约 430KB；`MaxRAMPercentage` 容器感知；非 root。
- **K8s（kind）**：无状态服务 Deployment，trade-service StatefulSet；gateway / trade HPA；就绪探针在订单簿恢复完成后才 UP；preStop 等待网关负载均衡缓存过期再停机；只设内存 limit 不设 CPU limit（避免 CFS 限流尖刺）。
