package com.tradesim.account.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.account.domain.LedgerMapper;
import com.tradesim.common.fund.FundKeys;
import com.tradesim.common.fund.FundStore;
import com.tradesim.common.market.MarketTick;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.common.web.BizException;
import com.tradesim.common.web.ErrorCode;
import com.tradesim.common.web.Headers;
import com.tradesim.common.web.Result;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final LedgerMapper ledger;
    private final FundStore fund;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final TradeRules rules;

    public AccountController(LedgerMapper ledger, FundStore fund, StringRedisTemplate redis, ObjectMapper om,
                             TradeRules rules) {
        this.ledger = ledger;
        this.fund = fund;
        this.redis = redis;
        this.om = om;
        this.rules = rules;
    }

    public record PositionView(String symbol, long qty, long avail, long frozen, long pending,
                               long cost, long last, long marketValue, long pnl) {}

    public record Overview(long initCash, long cash, long frozen, long marketValue, long totalAsset,
                           double returnPct, List<PositionView> positions) {}

    /** 实时资产：读 Redis 热数据 + 最新行情。 */
    @GetMapping("/overview")
    public Result<Overview> overview(@RequestHeader(Headers.USER_ID) long uid, @RequestParam long contestId) {
        Map<Object, Object> acct = redis.opsForHash().entries(FundKeys.account(uid, contestId));
        if (acct.isEmpty()) throw new BizException(ErrorCode.NO_ACCOUNT);
        long cash = num(acct.get("cash")), frozen = num(acct.get("frozen")), init = num(acct.get("init"));
        int today = rules.clock().tradingDay(System.currentTimeMillis());

        List<String> symbols = new ArrayList<>(Optional.ofNullable(
                redis.opsForSet().members(FundKeys.positionSet(uid, contestId))).orElse(Set.of()));
        Collections.sort(symbols);
        List<String> lasts = symbols.isEmpty() ? List.of()
                : redis.opsForValue().multiGet(symbols.stream().map(s -> "mkt:last:" + s).toList());

        List<PositionView> positions = new ArrayList<>();
        long mv = 0;
        for (int i = 0; i < symbols.size(); i++) {
            Map<Object, Object> p = redis.opsForHash().entries(FundKeys.position(uid, contestId, symbols.get(i)));
            long qty = num(p.get("qty"));
            if (qty == 0 && num(p.get("frozen")) == 0) continue;
            long avail = num(p.get("avail")), pending = num(p.get("pending"));
            if (num(p.get("day")) < today) {        // 展示时同样应用 T+1 惰性解锁
                avail += pending;
                pending = 0;
            }
            long last = lastPrice(lasts.get(i));
            long value = last * qty, cost = num(p.get("cost"));
            mv += value;
            positions.add(new PositionView(symbols.get(i), qty, avail, num(p.get("frozen")), pending, cost, last, value, value - cost));
        }
        long total = cash + frozen + mv;
        double ret = init == 0 ? 0 : (total - init) * 100.0 / init;
        return Result.ok(new Overview(init, cash, frozen, mv, total, Math.round(ret * 100) / 100.0, positions));
    }

    /** MySQL 账本视图（对账/排查用）。 */
    @GetMapping("/ledger")
    public Result<Map<String, Object>> ledgerView(@RequestHeader(Headers.USER_ID) long uid, @RequestParam long contestId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", ledger.account(uid, contestId));
        out.put("positions", ledger.positions(uid, contestId));
        return Result.ok(out);
    }

    private long lastPrice(String json) {
        if (json == null) return 0;
        try {
            return om.readValue(json, MarketTick.class).last();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long num(Object o) {
        return o == null ? 0 : Long.parseLong(o.toString());
    }
}
