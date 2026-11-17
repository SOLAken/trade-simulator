package com.tradesim.common.order;

/** Kafka order.event / trade.event 载荷，供 Flink 风控与收益计算。 */
public final class OrderEvent {
    private OrderEvent() {}

    public record Order(long orderId, long userId, long contestId, String symbol, int side, int type,
                        int status, long price, long qty, long filledQty, long ts) {}

    public record Trade(long tradeId, long orderId, long userId, long contestId, String symbol, int side,
                        long price, long qty, long amount, long fee, long ts) {}
}
