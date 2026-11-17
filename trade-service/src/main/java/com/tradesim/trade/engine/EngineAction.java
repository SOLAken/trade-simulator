package com.tradesim.trade.engine;

import com.tradesim.common.order.OrderStatus;

/** 撮合结果：引擎线程只产出动作，落库/发消息由结算通道异步执行。 */
public sealed interface EngineAction {
    long orderId();

    /** @param closeRest 成交后剩余部分是否直接终结（市价/止损单 IOC） */
    record Fill(long orderId, long price, long qty, boolean closeRest) implements EngineAction {}

    record Cancel(long orderId, OrderStatus status, String reason) implements EngineAction {}
}
