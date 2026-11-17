package com.tradesim.common.market;

/**
 * 行情快照。所有价格单位为「分」（long），避免浮点误差进入撮合与资金计算。
 * volume 为当日累计成交量（股），tradingDay 为 MarketClock 推算的交易日序号。
 */
public record MarketTick(
        String symbol,
        long last,
        long open,
        long high,
        long low,
        long prevClose,
        long limitUp,
        long limitDown,
        long volume,
        int tradingDay,
        long ts) {
}
