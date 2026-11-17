package com.tradesim.market.sim;

import com.tradesim.common.market.MarketTick;

/** 单只股票的当日状态，只被行情调度线程访问（单写者），无需加锁。 */
final class SymbolState {
    final String symbol;
    final PriceProcess process;
    int tradingDay;
    long prevClose;
    long open;
    long high;
    long low;
    long last;
    long volume;

    SymbolState(String symbol, PriceProcess process) {
        this.symbol = symbol;
        this.process = process;
    }

    void newDay(int day, long prevClose, long openPrice) {
        this.tradingDay = day;
        this.prevClose = prevClose;
        this.open = this.high = this.low = this.last = openPrice;
        this.volume = 0;
    }

    void apply(long price, long qty) {
        last = price;
        if (price > high) high = price;
        if (price < low) low = price;
        volume += qty;
    }

    MarketTick toTick(long limitUp, long limitDown, long ts) {
        return new MarketTick(symbol, last, open, high, low, prevClose, limitUp, limitDown, volume, tradingDay, ts);
    }
}
