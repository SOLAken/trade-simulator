package com.tradesim.common.mq;

/** 消息主题常量。Kafka 承载高吞吐事件流，RocketMQ 承载需要事务/延时语义的资金消息。 */
public final class Topics {
    private Topics() {}
    // Kafka
    public static final String MARKET_TICK = "market.tick";
    public static final String ORDER_EVENT = "order.event";
    public static final String TRADE_EVENT = "trade.event";
    public static final String RISK_ALERT = "risk.alert";
    public static final String RANK_UPDATE = "rank.update";
    // RocketMQ
    public static final String RMQ_ORDER_FREEZE = "ORDER_FREEZE";
    public static final String RMQ_TRADE_SETTLE = "TRADE_SETTLE";
    public static final String RMQ_ORDER_UNFREEZE = "ORDER_UNFREEZE";
    public static final String RMQ_ORDER_EXPIRE = "ORDER_EXPIRE";   // 延时消息：收盘撤单
}
