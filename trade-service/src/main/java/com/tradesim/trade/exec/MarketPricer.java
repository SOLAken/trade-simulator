package com.tradesim.trade.exec;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.Side;

/**
 * 市价成交价：最新价 ± 滑点，并受涨跌停约束。
 * 封板（买入时最新价已在涨停 / 卖出时在跌停）视为无对手盘，返回 -1。
 */
public final class MarketPricer {
    private MarketPricer() {}

    public static long fillPrice(Side side, MarketTick q, int slippageBps) {
        if (side == Side.BUY) {
            if (q.last() >= q.limitUp()) return -1;
            long px = ceilDiv(q.last() * (10_000 + slippageBps), 10_000);
            return Math.min(px, q.limitUp());
        }
        if (q.last() <= q.limitDown()) return -1;
        long px = q.last() * (10_000 - slippageBps) / 10_000;
        return Math.max(px, q.limitDown());
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
