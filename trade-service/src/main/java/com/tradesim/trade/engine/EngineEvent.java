package com.tradesim.trade.engine;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;

import java.util.concurrent.CompletableFuture;

/** RingBuffer 槽位对象：预分配、复用，处理完清空引用，稳态下撮合路径零对象分配（除动作本身）。 */
final class EngineEvent {
    enum Type { NEW, CANCEL, TICK }

    Type type;
    String symbol;
    BookOrder order;
    long cancelOrderId;
    OrderStatus cancelStatus;
    String cancelReason;
    MarketTick tick;
    /** NEW：同步产生的动作全部结算完成后完成；CANCEL：是否撤成 */
    CompletableFuture<Boolean> done;
    long publishNanos;

    void clear() {
        order = null;
        tick = null;
        done = null;
        symbol = null;
        cancelReason = null;
        cancelStatus = null;
    }
}
