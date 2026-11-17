package com.tradesim.common.rule;

import com.tradesim.common.market.MarketClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 交易规则，来源 Nacos trade-rules.yaml，@ConfigurationProperties 绑定后随配置热更新。
 * 注意：dayMinutes / breakSeconds / anchor 改动会让交易日序号整体漂移，只在重启时生效（见 clock()）。
 */
public class TradeRules {
    private BigDecimal feeRate = new BigDecimal("0.0003");
    private long minFeeCents = 500;
    private BigDecimal stampTaxRate = new BigDecimal("0.001");
    private BigDecimal limitPct = new BigDecimal("0.10");
    private int slippageBps = 5;
    private int dayMinutes = 30;
    private int breakSeconds = 60;
    private Instant anchor = Instant.parse("2026-01-01T00:00:00Z");

    private volatile MarketClock clock;

    public MarketClock clock() {
        MarketClock c = clock;
        if (c == null) {
            synchronized (this) {
                if (clock == null) clock = new MarketClock(anchor, dayMinutes, breakSeconds);
                c = clock;
            }
        }
        return c;
    }

    /** 涨停价（分），按 A 股规则四舍五入到分。 */
    public long limitUp(long prevCloseCents) {
        return BigDecimal.valueOf(prevCloseCents).multiply(BigDecimal.ONE.add(limitPct))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public long limitDown(long prevCloseCents) {
        return BigDecimal.valueOf(prevCloseCents).multiply(BigDecimal.ONE.subtract(limitPct))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 佣金（分）：成交额 × 费率，不足最低收费按最低收。 */
    public long commission(long amountCents) {
        long fee = BigDecimal.valueOf(amountCents).multiply(feeRate).setScale(0, RoundingMode.UP).longValueExact();
        return Math.max(fee, minFeeCents);
    }

    /** 印花税（分），仅卖出收取。 */
    public long stampTax(long amountCents) {
        return BigDecimal.valueOf(amountCents).multiply(stampTaxRate).setScale(0, RoundingMode.UP).longValueExact();
    }

    public BigDecimal getFeeRate() { return feeRate; }
    public void setFeeRate(BigDecimal feeRate) { this.feeRate = feeRate; }
    public long getMinFeeCents() { return minFeeCents; }
    public void setMinFeeCents(long minFeeCents) { this.minFeeCents = minFeeCents; }
    public BigDecimal getStampTaxRate() { return stampTaxRate; }
    public void setStampTaxRate(BigDecimal stampTaxRate) { this.stampTaxRate = stampTaxRate; }
    public BigDecimal getLimitPct() { return limitPct; }
    public void setLimitPct(BigDecimal limitPct) { this.limitPct = limitPct; }
    public int getSlippageBps() { return slippageBps; }
    public void setSlippageBps(int slippageBps) { this.slippageBps = slippageBps; }
    public int getDayMinutes() { return dayMinutes; }
    public void setDayMinutes(int dayMinutes) { this.dayMinutes = dayMinutes; }
    public int getBreakSeconds() { return breakSeconds; }
    public void setBreakSeconds(int breakSeconds) { this.breakSeconds = breakSeconds; }
    public Instant getAnchor() { return anchor; }
    public void setAnchor(Instant anchor) { this.anchor = anchor; }
}
