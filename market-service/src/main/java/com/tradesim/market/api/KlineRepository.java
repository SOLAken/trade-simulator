package com.tradesim.market.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * K 线查询。返回 ECharts candlestick 顺序：[ts, open, close, low, high, volume]。
 * - 5s/15s：直接对原始 tick 做 time_bucket（数据量小、要求实时）
 * - 1m：连续聚合 kline_1m（已物化 + 尾部实时聚合）
 * - 5m/15m：在 kline_1m 上二次 rollup，避免扫原始表
 * - 1d：按交易日聚合（加速市场里「一天」不是自然日）
 */
@Repository
public class KlineRepository {
    private static final Map<String, Long> SECONDS = Map.of("5s", 5L, "15s", 15L, "1m", 60L, "5m", 300L, "15m", 900L);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public KlineRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<long[]> query(String symbol, String interval, int limit) {
        if ("1d".equals(interval)) {
            return jdbc.query("""
                    SELECT * FROM (
                      SELECT min(ts) AS t, first(price, ts), last(price, ts), min(price), max(price), sum(qty)
                      FROM tick WHERE symbol = ? GROUP BY trading_day ORDER BY trading_day DESC LIMIT ?
                    ) d ORDER BY t""", this::row, symbol, limit);
        }
        Long sec = SECONDS.get(interval);
        if (sec == null) throw new IllegalArgumentException("unsupported interval " + interval);
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(sec * limit));
        String bucket = sec + " seconds";
        if (sec < 60) {
            return jdbc.query("""
                    SELECT time_bucket(?::interval, ts) AS t, first(price, ts), last(price, ts), min(price), max(price), sum(qty)
                    FROM tick WHERE symbol = ? AND ts >= ? GROUP BY t ORDER BY t""", this::row, bucket, symbol, since);
        }
        return jdbc.query("""
                SELECT time_bucket(?::interval, bucket) AS t, first(open, bucket), last(close, bucket), min(low), max(high), sum(volume)
                FROM kline_1m WHERE symbol = ? AND bucket >= ? GROUP BY t ORDER BY t""", this::row, bucket, symbol, since);
    }

    public void streamTicks(String symbol, Instant from, Instant to, OutputStream out) {
        // PG 只有在事务内 + fetchSize 才会用游标分批拉取，否则会把结果集全量读进内存
        tx.executeWithoutResult(s -> {
            JdbcTemplate cursor = new JdbcTemplate(jdbc.getDataSource());
            cursor.setFetchSize(1000);
            cursor.query("SELECT ts, price, qty FROM tick WHERE symbol = ? AND ts >= ? AND ts < ? ORDER BY ts",
                    rs -> {
                        String line = "[" + rs.getTimestamp(1).getTime() + "," + rs.getLong(2) + "," + rs.getLong(3) + "]\n";
                        try {
                            out.write(line.getBytes(StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    symbol, Timestamp.from(from), Timestamp.from(to));
        });
    }

    private long[] row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new long[]{rs.getTimestamp(1).getTime(), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)};
    }
}
