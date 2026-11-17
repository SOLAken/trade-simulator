package com.tradesim.trade.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.market.MarketTick;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class QuoteReader {
    private static final long STALE_MS = 5_000;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;

    public QuoteReader(StringRedisTemplate redis, ObjectMapper om) {
        this.redis = redis;
        this.om = om;
    }

    /** @return 最新行情；不存在或超过 5 秒未更新（行情中断）返回 null */
    public MarketTick latest(String symbol) {
        String json = redis.opsForValue().get("mkt:last:" + symbol);
        if (json == null) return null;
        try {
            MarketTick t = om.readValue(json, MarketTick.class);
            return System.currentTimeMillis() - t.ts() > STALE_MS ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
