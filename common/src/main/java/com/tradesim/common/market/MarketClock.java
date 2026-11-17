package com.tradesim.common.market;

import java.time.Instant;

/**
 * 加速市场时钟。所有服务用同一个 anchor + 日长推算交易日，无需互相通信即可对齐。
 * <pre>
 *  |<---- 交易时段 dayMs ---->|<- 休市 breakMs ->|<---- 下一交易日 ---->|
 *  anchor                     close              nextOpen
 * </pre>
 * 休市窗口用于：收盘撤单、T+1 持仓解冻、日终结算。
 */
public final class MarketClock {
    private final long anchorMs;
    private final long dayMs;
    private final long breakMs;
    private final long cycleMs;

    public MarketClock(Instant anchor, int dayMinutes, int breakSeconds) {
        this.anchorMs = anchor.toEpochMilli();
        this.dayMs = dayMinutes * 60_000L;
        this.breakMs = breakSeconds * 1000L;
        this.cycleMs = dayMs + breakMs;
    }

    /** 交易日序号，从 1 开始；anchor 之前返回 0。 */
    public int tradingDay(long nowMs) {
        if (nowMs < anchorMs) return 0;
        return (int) ((nowMs - anchorMs) / cycleMs) + 1;
    }

    public boolean isOpen(long nowMs) {
        return nowMs >= anchorMs && (nowMs - anchorMs) % cycleMs < dayMs;
    }

    /** 当前（或刚结束的）交易日的收盘时刻。 */
    public long closeAt(long nowMs) {
        return openAt(tradingDay(nowMs)) + dayMs;
    }

    public long openAt(int tradingDay) {
        return anchorMs + (long) (tradingDay - 1) * cycleMs;
    }

    public long nextOpenAt(long nowMs) {
        return openAt(tradingDay(nowMs) + 1);
    }

    public long dayMs() { return dayMs; }
}
