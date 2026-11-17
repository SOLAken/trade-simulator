package com.tradesim.trade.exec;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.trade.domain.OrderPO;
import com.tradesim.trade.ledger.OrderLedger;
import com.tradesim.trade.ledger.QuoteReader;
import org.springframework.stereotype.Component;

/** P2 执行端：市价单按最新价立即全部成交，否则撤销（IOC）。限价/止损单在 P3 由撮合引擎接管。 */
@Component
public class SimpleRouter implements OrderRouter {
    private final OrderLedger ledger;
    private final QuoteReader quotes;
    private final TradeRules rules;

    public SimpleRouter(OrderLedger ledger, QuoteReader quotes, TradeRules rules) {
        this.ledger = ledger;
        this.quotes = quotes;
        this.rules = rules;
    }

    @Override
    public void accept(OrderPO o) {
        if (o.type != OrderType.MARKET.code) {
            ledger.cancel(o.id, OrderStatus.REJECTED, "暂不支持该订单类型");
            return;
        }
        MarketTick q = quotes.latest(o.symbol);
        long px = q == null ? -1 : MarketPricer.fillPrice(Side.of(o.side), q, rules.getSlippageBps());
        if (px < 0) {
            ledger.cancel(o.id, OrderStatus.CANCELED, q == null ? "行情中断" : "封板无对手盘");
            return;
        }
        ledger.settle(o.id, px, o.qty, true);
    }

    @Override
    public boolean cancel(OrderPO o) {
        return ledger.cancel(o.id, OrderStatus.CANCELED, "用户撤单");
    }
}
