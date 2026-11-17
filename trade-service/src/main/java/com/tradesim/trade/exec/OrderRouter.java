package com.tradesim.trade.exec;

import com.tradesim.trade.domain.OrderPO;

import java.util.concurrent.CompletableFuture;

/** 冻结成功后的订单交给执行端（撮合引擎）。 */
public interface OrderRouter {
    /** @return 下单同步产生的成交/撤单全部落库后完成（市价单据此返回最终状态） */
    CompletableFuture<Boolean> accept(OrderPO order);

    /** @return true 撤单成功 */
    boolean cancel(OrderPO order);
}
