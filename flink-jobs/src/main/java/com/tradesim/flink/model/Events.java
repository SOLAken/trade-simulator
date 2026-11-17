package com.tradesim.flink.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Kafka 事件的 Flink 侧 POJO（public 字段 + 无参构造 = Flink POJO 序列化，比 Kryo 快且支持状态演进）。
 * 字段与 common 中的 OrderEvent / MarketTick 对应；id 可能以字符串形式出现（>2^53），Jackson 自动转换。
 */
public final class Events {
    private Events() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Order {
        public long orderId, userId, contestId, price, qty, filledQty, ts;
        public String symbol;
        public int side, type, status;
        public Order() {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trade {
        public long tradeId, orderId, userId, contestId, price, qty, amount, fee, ts;
        public String symbol;
        public int side;
        public Trade() {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tick {
        public String symbol;
        public long last, ts;
        public int tradingDay;
        public Tick() {}
    }

    /** 实时收益：pnl = 已实现现金流 + 持仓市值（不含初始资金，rank-service 加上 initCash 得总资产） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RankUpdate {
        public long userId, contestId, pnl, ts;
        public RankUpdate() {}
        public RankUpdate(long userId, long contestId, long pnl, long ts) {
            this.userId = userId; this.contestId = contestId; this.pnl = pnl; this.ts = ts;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Alert {
        public long userId, contestId, ts;
        public String type, detail;
        public Alert() {}
        public Alert(long userId, long contestId, String type, String detail, long ts) {
            this.userId = userId; this.contestId = contestId; this.type = type; this.detail = detail; this.ts = ts;
        }
    }

    /** 风控阈值（由 risk-service 从 Nacos 同步到 risk.rules topic） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RiskRules {
        public int maxOrdersPer10s = 20;
        public int quickCancelMillis = 3000;
        public int maxQuickCancelsPer30s = 8;
        public double abnormalReturnZscore = 4.0;
        public int minPeersForZscore = 5;
        public long minStdCents = 1_000_000;      // 标准差下限 1 万元
        public RiskRules() {}
    }
}
