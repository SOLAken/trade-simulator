package com.tradesim.rank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.fund.FundKeys;
import com.tradesim.common.market.MarketTick;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 批量计算总资产 = 可用 + 冻结 + Σ 持仓 × 最新价。
 * 两轮 pipeline：第一轮取资金 hash + 持仓股票集合，第二轮取所有持仓数量。N 个用户只需 2 次网络往返。
 */
@Component
public class EquityCalculator {
    private final StringRedisTemplate redis;
    private final ObjectMapper om;

    public EquityCalculator(StringRedisTemplate redis, ObjectMapper om) {
        this.redis = redis;
        this.om = om;
    }

    public Map<Long, Long> totals(long contestId, List<Long> users, Map<String, Long> prices) {
        if (users.isEmpty()) return Map.of();
        List<Object> r1 = redis.executePipelined((RedisCallback<Object>) c -> {
            for (long u : users) {
                c.hashCommands().hMGet(bytes(FundKeys.account(u, contestId)), bytes("cash"), bytes("frozen"));
                c.setCommands().sMembers(bytes(FundKeys.positionSet(u, contestId)));
            }
            return null;
        });
        Map<Long, Long> totals = new LinkedHashMap<>();
        List<long[]> posRefs = new ArrayList<>();      // [userIndex]
        List<String> posKeys = new ArrayList<>();
        List<String> posSymbols = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            @SuppressWarnings("unchecked") List<Object> cf = (List<Object>) r1.get(2 * i);
            if (cf == null || cf.get(0) == null) continue;           // 未开户
            totals.put(users.get(i), num(cf.get(0)) + num(cf.get(1)));
            @SuppressWarnings("unchecked") Set<Object> syms = (Set<Object>) r1.get(2 * i + 1);
            if (syms == null) continue;
            for (Object s : syms) {
                posRefs.add(new long[]{users.get(i)});
                posKeys.add(FundKeys.position(users.get(i), contestId, s.toString()));
                posSymbols.add(s.toString());
            }
        }
        if (!posKeys.isEmpty()) {
            List<Object> qtys = redis.executePipelined((RedisCallback<Object>) c -> {
                for (String k : posKeys) c.hashCommands().hGet(bytes(k), bytes("qty"));
                return null;
            });
            for (int i = 0; i < posKeys.size(); i++) {
                long mv = num(qtys.get(i)) * prices.getOrDefault(posSymbols.get(i), 0L);
                totals.merge(posRefs.get(i)[0], mv, Long::sum);
            }
        }
        return totals;
    }

    /** 全部股票最新价（一次 KEYS 不可取，故按已知股票列表 MGET；列表来自行情快照 key 扫描缓存）。 */
    public Map<String, Long> prices() {
        Map<String, Long> out = new HashMap<>();
        Set<String> keys = new HashSet<>();
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match("mkt:last:*").count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        List<String> keyList = new ArrayList<>(keys);
        List<String> vals = redis.opsForValue().multiGet(keyList);
        for (int i = 0; vals != null && i < keyList.size(); i++) {
            try {
                MarketTick t = om.readValue(vals.get(i), MarketTick.class);
                out.put(t.symbol(), t.last());
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static long num(Object o) {
        return o == null ? 0 : Long.parseLong(o.toString());
    }
}
