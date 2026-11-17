package com.tradesim.account.api;

import com.tradesim.account.domain.LedgerMapper;
import com.tradesim.common.contest.ContestKeys;
import com.tradesim.common.fund.FundKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对账：Redis 热数据（下单校验用）vs MySQL 账本（消息驱动）。
 * <p>
 * 两者通过 RocketMQ 异步收敛，任一时刻都可能有在途消息，单次比对会误报。
 * 因此做两遍：第一遍找出差异，等待 settleMillis 后只复核这些账户，差异值不变才判定为真实不一致。
 * 比对口径：资金 (cash, frozen)、持仓 (qty, frozen)。持仓成本只在 Redis 维护，不参与对账。
 */
@Component
public class Reconciler {
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final LedgerMapper ledger;
    private final StringRedisTemplate redis;

    public Reconciler(LedgerMapper ledger, StringRedisTemplate redis) {
        this.ledger = ledger;
        this.redis = redis;
    }

    public record Mismatch(long userId, String item, long mysql, long redis) {}

    public record Report(long contestId, int accounts, int positions, int firstPassDiffs, List<Mismatch> mismatches,
                         long millis) {}

    public Report run(long contestId, long settleMillis) throws InterruptedException {
        long start = System.currentTimeMillis();
        Snapshot first = snapshot(contestId);
        Map<String, Mismatch> diffs = diff(first);
        List<Mismatch> confirmed = new ArrayList<>();
        if (!diffs.isEmpty()) {
            Thread.sleep(settleMillis);
            Map<String, Mismatch> again = diff(snapshot(contestId));
            diffs.forEach((k, m) -> {
                if (m.equals(again.get(k))) confirmed.add(m);
            });
        }
        return new Report(contestId, first.accounts.size(), first.positions.size(), diffs.size(), confirmed,
                System.currentTimeMillis() - start);
    }

    /** 每 5 分钟对所有进行中的比赛对账，发现不一致打 ERROR 日志（接入告警系统即可通知）。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scheduled() throws InterruptedException {
        for (String cid : Optional.ofNullable(redis.opsForSet().members(ContestKeys.ACTIVE)).orElse(Set.of())) {
            Report r = run(Long.parseLong(cid), 3000);
            if (!r.mismatches().isEmpty()) log.error("RECONCILE MISMATCH contest={} {}", cid, r.mismatches());
            else log.info("reconcile ok contest={} accounts={} positions={} in {}ms", cid, r.accounts(), r.positions(), r.millis());
        }
    }

    // ------------------------------------------------------------------

    private record Snapshot(List<Map<String, Object>> accounts, List<Map<String, Object>> positions,
                            List<Object> redisAccounts, List<Object> redisPositions) {}

    private Snapshot snapshot(long cid) {
        List<Map<String, Object>> accounts = ledger.allAccounts(cid);
        List<Map<String, Object>> positions = ledger.allPositions(cid);
        // 两轮 pipeline 读 Redis，N 个账户也只有 2 次往返
        List<Object> ra = redis.executePipelined((RedisCallback<Object>) c -> {
            for (var a : accounts) c.hashCommands().hMGet(b(FundKeys.account(l(a, "user_id"), cid)), b("cash"), b("frozen"));
            return null;
        });
        List<Object> rp = redis.executePipelined((RedisCallback<Object>) c -> {
            for (var p : positions) {
                c.hashCommands().hMGet(b(FundKeys.position(l(p, "user_id"), cid, (String) p.get("symbol"))), b("qty"), b("frozen"));
            }
            return null;
        });
        return new Snapshot(accounts, positions, ra, rp);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Mismatch> diff(Snapshot s) {
        Map<String, Mismatch> out = new LinkedHashMap<>();
        for (int i = 0; i < s.accounts.size(); i++) {
            var a = s.accounts.get(i);
            List<Object> r = (List<Object>) s.redisAccounts.get(i);
            long uid = l(a, "user_id");
            cmp(out, uid, "cash", l(a, "cash"), num(r.get(0)));
            cmp(out, uid, "frozen", l(a, "frozen"), num(r.get(1)));
        }
        for (int i = 0; i < s.positions.size(); i++) {
            var p = s.positions.get(i);
            List<Object> r = (List<Object>) s.redisPositions.get(i);
            long uid = l(p, "user_id");
            String sym = (String) p.get("symbol");
            cmp(out, uid, sym + ".qty", l(p, "qty"), num(r.get(0)));
            cmp(out, uid, sym + ".frozen", l(p, "frozen"), num(r.get(1)));
        }
        return out;
    }

    private static void cmp(Map<String, Mismatch> out, long uid, String item, long mysql, long redis) {
        if (mysql != redis) out.put(uid + "/" + item, new Mismatch(uid, item, mysql, redis));
    }

    private static long l(Map<String, Object> row, String k) {
        return ((Number) row.get(k)).longValue();
    }

    private static long num(Object o) {
        return o == null ? 0 : Long.parseLong(o.toString());
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
