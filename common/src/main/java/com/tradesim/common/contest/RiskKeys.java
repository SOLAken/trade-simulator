package com.tradesim.common.contest;

/** 风控 Redis key（risk-service 写，trade-service 读）。 */
public final class RiskKeys {
    private RiskKeys() {}

    /** string，带 TTL：存在即禁止下单，值为原因 */
    public static String block(long userId) { return "risk:block:" + userId; }

    /** list：最近告警（LPUSH + LTRIM） */
    public static final String ALERTS = "risk:alerts";
}
