package com.tradesim.trade.ledger;

import com.tradesim.common.order.Side;
import com.tradesim.common.rule.TradeRules;

/**
 * 一笔成交的资金计算（纯函数）。
 * 买：used = 成交额 + 佣金（从冻结资金扣）；卖：used = 股数，到账 = 成交额 - 佣金 - 印花税。
 */
public record FillCalc(long amount, long commission, long tax, long used) {

    public static FillCalc of(Side side, long price, long qty, TradeRules rules) {
        long amount = Math.multiplyExact(price, qty);
        long commission = rules.commission(amount);
        if (side == Side.BUY) return new FillCalc(amount, commission, 0, amount + commission);
        return new FillCalc(amount, commission, rules.stampTax(amount), qty);
    }

    /** 买单下单时冻结的资金：按给定价（限价或涨停价）估算成交额 + 佣金。 */
    public static long buyFreeze(long price, long qty, TradeRules rules) {
        long amount = Math.multiplyExact(price, qty);
        return amount + rules.commission(amount);
    }

    public long fee() {
        return commission + tax;
    }
}
