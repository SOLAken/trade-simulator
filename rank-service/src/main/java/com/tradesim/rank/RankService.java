package com.tradesim.rank;

import com.tradesim.common.contest.ContestKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RankService {
    private final StringRedisTemplate redis;

    public RankService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Entry(int rank, long userId, String nickname, long total, double returnPct) {}

    public record Board(long contestId, long initCash, int participants, List<Entry> top, Entry me) {}

    /** ZREVRANGE 取前 N + ZREVRANK/ZSCORE 取自己的名次：都是 O(log N) 级别。 */
    public Board board(long contestId, int topN, Long me) {
        String key = ContestKeys.rank(contestId);
        Object ic = redis.opsForHash().get(ContestKeys.meta(contestId), "initCash");
        long init = ic == null ? 0 : Long.parseLong(ic.toString());
        Set<ZSetOperations.TypedTuple<String>> top = Optional.ofNullable(
                redis.opsForZSet().reverseRangeWithScores(key, 0, topN - 1)).orElse(Set.of());
        List<String> ids = top.stream().map(ZSetOperations.TypedTuple::getValue).toList();
        List<Object> nicks = ids.isEmpty() ? List.of()
                : redis.opsForHash().multiGet(ContestKeys.NICKNAMES, new ArrayList<>(ids));
        List<Entry> entries = new ArrayList<>();
        int i = 0;
        for (var t : top) {
            long total = t.getScore().longValue();
            entries.add(new Entry(i + 1, Long.parseLong(t.getValue()), nick(nicks.get(i), t.getValue()), total, pct(total, init)));
            i++;
        }
        Entry mine = null;
        if (me != null) {
            Long r = redis.opsForZSet().reverseRank(key, me.toString());
            Double s = redis.opsForZSet().score(key, me.toString());
            if (r != null && s != null) {
                Object n = redis.opsForHash().get(ContestKeys.NICKNAMES, me.toString());
                mine = new Entry(r.intValue() + 1, me, nick(n, me.toString()), s.longValue(), pct(s.longValue(), init));
            }
        }
        Long size = redis.opsForZSet().zCard(key);
        return new Board(contestId, init, size == null ? 0 : size.intValue(), entries, mine);
    }

    private static String nick(Object n, String fallback) {
        return n == null ? "用户" + fallback : n.toString();
    }

    private static double pct(long total, long init) {
        return init == 0 ? 0 : Math.round((total - init) * 10000.0 / init) / 100.0;
    }
}
