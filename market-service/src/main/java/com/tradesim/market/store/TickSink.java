package com.tradesim.market.store;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.mq.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * tick 的三路下游：
 * <ol>
 *   <li>Redis 快照（由 MarketSimulator MSET）——给下单校验、前端首屏用</li>
 *   <li>Kafka market.tick，key=symbol——同一股票落同一分区，撮合端按序消费</li>
 *   <li>TimescaleDB——有界队列 + 每秒批量写，DB 慢时丢弃并告警，绝不阻塞行情线程</li>
 * </ol>
 */
@Component
public class TickSink {
    private static final Logger log = LoggerFactory.getLogger(TickSink.class);
    private static final String INSERT =
            "INSERT INTO tick(ts, symbol, price, qty, trading_day) VALUES (?, ?, ?, ?, ?)";

    private final KafkaTemplate<String, String> kafka;
    private final JdbcTemplate jdbc;
    private final BlockingQueue<Object[]> dbQueue = new ArrayBlockingQueue<>(50_000);
    private final Map<String, Long> lastVolume = new HashMap<>();   // 仅行情线程访问
    private long dropped;

    public TickSink(KafkaTemplate<String, String> kafka, JdbcTemplate jdbc) {
        this.kafka = kafka;
        this.jdbc = jdbc;
    }

    /** @param json 已序列化的 tick，key 为 Redis key，避免二次序列化 */
    public void accept(List<MarketTick> ticks, Map<String, String> json) {
        for (MarketTick t : ticks) {
            kafka.send(Topics.MARKET_TICK, t.symbol(), json.get("mkt:last:" + t.symbol()));
            Long prev = lastVolume.get(t.symbol());
            // 重启后首个 tick 不知道上一笔累计量，记 0（否则会把当日全部累计量算进一根 K 线）；跨日累计量归零
            long qty = prev == null ? 0 : t.volume() >= prev ? t.volume() - prev : t.volume();
            lastVolume.put(t.symbol(), t.volume());
            if (!dbQueue.offer(new Object[]{new Timestamp(t.ts()), t.symbol(), t.last(), qty, t.tradingDay()})) {
                if (++dropped % 1000 == 1) log.warn("tick db queue full, dropped={}", dropped);
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<Object[]> rows = new ArrayList<>(dbQueue.size());
        dbQueue.drainTo(rows);
        if (rows.isEmpty()) return;
        try {
            jdbc.batchUpdate(INSERT, rows);
        } catch (Exception e) {
            log.error("flush {} ticks failed", rows.size(), e);
        }
    }
}
