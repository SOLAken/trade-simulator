package com.tradesim.trade.exec;

import com.tradesim.trade.domain.OrderPO;

/** 冻结成功后的订单交给执行端。P2：市价单同步成交；P3：撮合引擎。 */
public interface OrderRouter {
    void accept(OrderPO order);

    /** @return true 已受理撤单请求 */
    boolean cancel(OrderPO order);
}
