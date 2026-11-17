package com.tradesim.market.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.market.MarketClock;
import com.tradesim.common.market.MarketTick;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.market.store.TickSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 行情模拟器：每 tickMillis 为每只股票生成一笔 tick。
 * 只有持有 Redis 租约的实例生成；接管时从 Redis 最新快照恢复，保证价格连续。
 */
@Component
public class MarketSimulator {
    private static final Logger log = LoggerFactory.getLogger(MarketSimulator.class);
    public static final String LAST_KEY = "mkt:last:";

    private final MarketProps props;
    private final TradeRules rules;
    private final LeaderLease lease;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final TickSink sink;
    private final Map<String, SymbolState> states = new LinkedHashMap<>();
    private boolean leader;

    public MarketSimulator(MarketProps props, TradeRules rules, LeaderLease lease,
                           StringRedisTemplate redis, ObjectMapper om, TickSink sink) {
        this.props = props;
        this.rules = rules;
        this.lease = lease;
        this.redis = redis;
        this.om = om;
        this.sink = sink;
    }

    @Scheduled(fixedRateString = "${market.tick-millis}")
    public void tick() {
        boolean nowLeader = lease.tryHold();
        if (nowLeader && !leader) {
            log.info("acquired market leader lease, restoring state from redis");
            restore();
        }
        leader = nowLeader;
        if (!leader) return;

        long now = System.currentTimeMillis();
        MarketClock clock = rules.clock();
        if (!clock.isOpen(now)) return;
        int day = clock.tradingDay(now);
        double dtDays = (double) props.tickMillis() / clock.dayMs();

        List<MarketTick> batch = new ArrayList<>(states.size());
        for (SymbolState s : states.values()) {
            if (s.tradingDay != day) {
                // 跨日：昨收 = 上一交易日最后价；首次启动则 = 初始价
                long prevClose = s.last;
                long openPx = clampToLimit(Math.round(s.process.openGap(prevClose)), prevClose);
                s.newDay(day, prevClose, openPx);
            } else {
                double fair = s.process.step(dtDays);
                long px = clampToLimit(Math.round(fair), s.prevClose);
                s.apply(px, simulatedQty(s, px));
            }
            batch.add(s.toTick(rules.limitUp(s.prevClose), rules.limitDown(s.prevClose), now));
        }
        publish(batch);
    }

    /** 成交量：基础量 × 价格波动放大，按手（100 股）取整。 */
    private static long simulatedQty(SymbolState s, long px) {
        double move = s.last == 0 ? 0 : Math.abs(px - s.last) / (double) s.last;
        double base = 50 + ThreadLocalRandom.current().nextDouble() * 150;
        return (long) (base * (1 + move * 500)) * 100;
    }

    private long clampToLimit(long px, long prevClose) {
        return Math.max(rules.limitDown(prevClose), Math.min(rules.limitUp(prevClose), Math.max(px, 1)));
    }

    private void publish(List<MarketTick> batch) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (MarketTick t : batch) {
            try {
                snapshot.put(LAST_KEY + t.symbol(), om.writeValueAsString(t));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        redis.opsForValue().multiSet(snapshot);           // 一次 MSET，单 RTT
        sink.accept(batch, snapshot);
    }

    /** 首次成为主：优先 Redis 快照，否则用配置的初始价。 */
    private void restore() {
        states.clear();
        long seed = System.nanoTime();
        for (MarketProps.SymbolDef d : props.symbols()) {
            String json = redis.opsForValue().get(LAST_KEY + d.symbol());
            MarketTick last = null;
            if (json != null) {
                try {
                    last = om.readValue(json, MarketTick.class);
                } catch (JsonProcessingException e) {
                    log.warn("bad snapshot for {}, reinit", d.symbol());
                }
            }
            long start = last != null ? last.last() : d.initCents();
            var proc = new PriceProcess(start, d.mu(), d.sigma(), props.jumpProb(), props.jumpSigma(),
                    new SplittableRandom(seed ^ d.symbol().hashCode()));
            var st = new SymbolState(d.symbol(), proc);
            if (last != null) {
                st.newDay(last.tradingDay(), last.prevClose(), last.open());
                st.high = last.high();
                st.low = last.low();
                st.last = last.last();
                st.volume = last.volume();
            } else {
                st.last = start;      // tradingDay=0，下一 tick 触发 newDay，昨收 = 初始价
            }
            states.put(d.symbol(), st);
        }
    }

    public List<MarketProps.SymbolDef> symbols() {
        return props.symbols();
    }
}
