package com.tradesim.rank;

import com.tradesim.common.contest.ContestKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

/**
 * 排行榜全量快照：每 2 秒按最新价重算所有参赛者总资产 → ZSet(score=总资产)。
 * <p>
 * 定位是「校准」：P5 起 Flink 按成交事件做实时增量（rank.update），这里周期性用 Redis 真实持仓纠偏，
 * 两者互补——增量保证实时性，全量保证最终正确（Flink 状态丢失/事件丢失也能自愈）。
 * 多实例部署时用 Redis 锁保证同一时刻只有一个实例在算。
 */
@Component
public class RankSnapshotJob {
    private static final Logger log = LoggerFactory.getLogger(RankSnapshotJob.class);
    private static final String LOCK = "rank:snapshot:lock";

    private final StringRedisTemplate redis;
    private final EquityCalculator calc;
    private final JdbcTemplate jdbc;
    private final RankWsHandler ws;
    private final int equityEvery;
    private final String owner = UUID.randomUUID().toString();
    private long runs;

    public RankSnapshotJob(StringRedisTemplate redis, EquityCalculator calc, JdbcTemplate jdbc, RankWsHandler ws,
                           @Value("${rank.equity-every:5}") int equityEvery) {
        this.redis = redis;
        this.calc = calc;
        this.jdbc = jdbc;
        this.ws = ws;
        this.equityEvery = equityEvery;
    }

    @Scheduled(fixedDelayString = "${rank.snapshot-millis:2000}")
    public void snapshot() {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, owner, Duration.ofMillis(1800)))) return;
        boolean writeEquity = ++runs % equityEvery == 0;
        long now = System.currentTimeMillis();
        Map<String, Long> prices = calc.prices();
        List<Object[]> equityRows = new ArrayList<>();
        for (String cid : Optional.ofNullable(redis.opsForSet().members(ContestKeys.ACTIVE)).orElse(Set.of())) {
            long contestId = Long.parseLong(cid);
            Object endAt = redis.opsForHash().get(ContestKeys.meta(contestId), "endAt");
            if (endAt != null && Long.parseLong(endAt.toString()) < now) {
                redis.opsForSet().remove(ContestKeys.ACTIVE, cid);   // 比赛结束：榜单冻结，不再更新
                continue;
            }
            List<Long> users = Optional.ofNullable(redis.opsForSet().members(ContestKeys.members(contestId)))
                    .orElse(Set.of()).stream().map(Long::parseLong).toList();
            Map<Long, Long> totals = calc.totals(contestId, users, prices);
            if (totals.isEmpty()) continue;
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            totals.forEach((u, t) -> tuples.add(new DefaultTypedTuple<>(u.toString(), (double) t)));
            redis.opsForZSet().add(ContestKeys.rank(contestId), tuples);
            ws.publish(contestId);
            if (writeEquity) {
                Timestamp ts = new Timestamp(now);
                totals.forEach((u, t) -> equityRows.add(new Object[]{ts, contestId, u, t}));
            }
        }
        if (!equityRows.isEmpty()) {
            try {
                jdbc.batchUpdate("INSERT INTO equity(ts, contest_id, user_id, total) VALUES (?, ?, ?, ?)", equityRows);
            } catch (Exception e) {
                log.warn("equity write failed: {}", e.toString());
            }
        }
    }
}
