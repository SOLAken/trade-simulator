package com.tradesim.common;

import com.tradesim.common.market.MarketClock;
import com.tradesim.common.rule.TradeRules;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarketClockAndRulesTest {
    private final Instant anchor = Instant.parse("2026-01-01T00:00:00Z");
    private final long a = anchor.toEpochMilli();
    private final MarketClock clock = new MarketClock(anchor, 30, 60);

    @Test
    void sessionBoundaries() {
        assertThat(clock.tradingDay(a - 1)).isZero();
        assertThat(clock.isOpen(a)).isTrue();
        assertThat(clock.tradingDay(a)).isEqualTo(1);
        long close = a + 30 * 60_000L;
        assertThat(clock.isOpen(close - 1)).isTrue();
        assertThat(clock.isOpen(close)).isFalse();          // 进入休市
        assertThat(clock.closeAt(a + 1000)).isEqualTo(close);
        long nextOpen = close + 60_000L;
        assertThat(clock.isOpen(nextOpen)).isTrue();
        assertThat(clock.tradingDay(nextOpen)).isEqualTo(2);
        assertThat(clock.nextOpenAt(close + 1)).isEqualTo(nextOpen);
    }

    @Test
    void limitsAndFees() {
        TradeRules r = new TradeRules();
        assertThat(r.limitUp(1005)).isEqualTo(1106);     // 10.05 * 1.1 = 11.055 → 11.06
        assertThat(r.limitDown(1005)).isEqualTo(905);    // 9.045 → 9.05
        assertThat(r.commission(1_000_00)).isEqualTo(500);         // 1000 元 × 万三 = 0.3 元 < 5 元
        assertThat(r.commission(100_000_00)).isEqualTo(3000);      // 10 万 × 万三 = 30 元
        assertThat(r.stampTax(100_000_00)).isEqualTo(10000);       // 千一 = 100 元
    }
}
