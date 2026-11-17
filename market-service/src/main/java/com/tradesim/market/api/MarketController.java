package com.tradesim.market.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.market.MarketTick;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.common.web.Result;
import com.tradesim.market.sim.MarketProps;
import com.tradesim.market.sim.MarketSimulator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final MarketSimulator sim;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final KlineRepository klines;
    private final TradeRules rules;

    public MarketController(MarketSimulator sim, StringRedisTemplate redis, ObjectMapper om,
                            KlineRepository klines, TradeRules rules) {
        this.sim = sim;
        this.redis = redis;
        this.om = om;
        this.klines = klines;
        this.rules = rules;
    }

    public record SymbolView(String symbol, String name, MarketTick quote) {}

    public record ClockView(int tradingDay, boolean open, long closeAt, long nextOpenAt, long serverTime) {}

    @GetMapping("/symbols")
    public Result<List<SymbolView>> symbols() {
        List<MarketProps.SymbolDef> defs = sim.symbols();
        List<String> keys = defs.stream().map(d -> MarketSimulator.LAST_KEY + d.symbol()).toList();
        List<String> vals = Optional.ofNullable(redis.opsForValue().multiGet(keys)).orElse(List.of());
        List<SymbolView> out = new ArrayList<>(defs.size());
        for (int i = 0; i < defs.size(); i++) {
            out.add(new SymbolView(defs.get(i).symbol(), defs.get(i).name(), parse(i < vals.size() ? vals.get(i) : null)));
        }
        return Result.ok(out);
    }

    @GetMapping("/quote/{symbol}")
    public Result<MarketTick> quote(@PathVariable String symbol) {
        return Result.ok(parse(redis.opsForValue().get(MarketSimulator.LAST_KEY + symbol)));
    }

    @GetMapping("/clock")
    public Result<ClockView> clock() {
        long now = System.currentTimeMillis();
        var c = rules.clock();
        return Result.ok(new ClockView(c.tradingDay(now), c.isOpen(now), c.closeAt(now), c.nextOpenAt(now), now));
    }

    /** @param interval 5s|15s|1m|5m|15m|1d */
    @GetMapping("/kline")
    public Result<List<long[]>> kline(@RequestParam String symbol,
                                      @RequestParam(defaultValue = "1m") String interval,
                                      @RequestParam(defaultValue = "300") int limit) {
        return Result.ok(klines.query(symbol, interval, Math.min(limit, 2000)));
    }

    /** 历史回放：按时间区间流式输出 NDJSON（每行 [ts, price, qty]），不在内存中堆积。 */
    @GetMapping(value = "/replay", produces = "application/x-ndjson")
    public StreamingResponseBody replay(@RequestParam String symbol,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to) {
        return out -> klines.streamTicks(symbol, from, to, out);
    }

    private MarketTick parse(String json) {
        if (json == null) return null;
        try {
            return om.readValue(json, MarketTick.class);
        } catch (Exception e) {
            return null;
        }
    }
}
