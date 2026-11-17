package com.tradesim.trade.engine;

import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;

/** 订单簿中的订单（只被所属分片线程读写）。 */
public final class BookOrder {
    final long orderId;
    final long userId;
    final String symbol;
    final Side side;
    final OrderType type;
    final long price;
    final long stopPrice;
    final int tradingDay;
    long remaining;
    long seq;           // 时间优先序号，分片内单调递增

    public BookOrder(long orderId, long userId, String symbol, Side side, OrderType type,
                     long price, long stopPrice, long remaining, int tradingDay) {
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.stopPrice = stopPrice;
        this.remaining = remaining;
        this.tradingDay = tradingDay;
    }

    public long orderId() { return orderId; }
    public long remaining() { return remaining; }
}
