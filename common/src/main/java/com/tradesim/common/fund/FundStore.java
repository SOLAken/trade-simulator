package com.tradesim.common.fund;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 资金/持仓热数据的唯一操作入口：所有变更都是 Lua 原子脚本 + 幂等标记。
 * <p>
 * trade-service 用它做下单前置冻结与成交结算（低延迟，无需 RPC）；
 * account-service 用它开户与查询。MySQL 账本由 account-service 消费 RocketMQ 消息独立维护，
 * 两边通过对账任务校验一致。
 */
public class FundStore {
    public static final int OK = 1, DUPLICATE = 2, INSUFFICIENT = -1, NO_ACCOUNT = -2;
    private static final String TTL = Long.toString(Duration.ofDays(3).toSeconds());

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> freezeCash = load("freeze_cash");
    private final DefaultRedisScript<Long> freezePosition = load("freeze_position");
    private final DefaultRedisScript<Long> settle = load("settle");
    private final DefaultRedisScript<Long> release = load("release");
    private final DefaultRedisScript<Long> openAccount = load("open_account");

    public FundStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public int freezeCash(long uid, long cid, long orderId, long amount) {
        return exec(freezeCash, List.of(FundKeys.account(uid, cid), FundKeys.marker("frz", uid, orderId)),
                amount, TTL);
    }

    public int freezePosition(long uid, long cid, String symbol, long orderId, long qty, int tradingDay) {
        return exec(freezePosition, List.of(FundKeys.position(uid, cid, symbol), FundKeys.marker("frz", uid, orderId)),
                qty, TTL, tradingDay);
    }

    /** @return true 本次生效，false 重复（已结算过） */
    public boolean settle(long uid, long cid, String symbol, long tradeId, int side, long qty, long amount,
                          long fee, long release, int tradingDay) {
        return exec(settle, List.of(FundKeys.account(uid, cid), FundKeys.position(uid, cid, symbol),
                        FundKeys.marker("stl", uid, tradeId), FundKeys.positionSet(uid, cid)),
                side, qty, amount, fee, release, tradingDay, TTL, symbol) == 1;
    }

    public boolean release(long uid, long cid, String symbol, long orderId, int side, long release, int tradingDay) {
        String key = side == 1 ? FundKeys.account(uid, cid) : FundKeys.position(uid, cid, symbol);
        return exec(this.release, List.of(key, FundKeys.marker("rel", uid, orderId)), side, release, TTL, tradingDay) == 1;
    }

    public boolean openAccount(long uid, long cid, long initCash) {
        return exec(openAccount, List.of(FundKeys.account(uid, cid)), initCash) == 1;
    }

    /** 回滚一次冻结（本地事务失败的补偿）。删除 frz 标记以便同一订单号可安全重试。 */
    public void undoFreeze(long uid, long cid, String symbol, long orderId, int side, long amount) {
        String key = side == 1 ? FundKeys.account(uid, cid) : FundKeys.position(uid, cid, symbol);
        String field = side == 1 ? "cash" : "avail";
        redis.opsForHash().increment(key, "frozen", -amount);
        redis.opsForHash().increment(key, field, amount);
        redis.delete(FundKeys.marker("frz", uid, orderId));
    }

    private int exec(DefaultRedisScript<Long> script, List<String> keys, Object... args) {
        String[] a = new String[args.length];
        for (int i = 0; i < args.length; i++) a[i] = String.valueOf(args[i]);
        Long r = redis.execute(script, keys, (Object[]) a);
        return r == null ? 0 : r.intValue();
    }

    /** 加载脚本并展开 --#include（复用 T+1 解锁函数）。 */
    private static DefaultRedisScript<Long> load(String name) {
        String src = read("lua/" + name + ".lua");
        if (src.contains("--#include t1_roll")) {
            src = src.replace("--#include t1_roll", read("lua/t1_roll.lua.inc"));
        }
        return new DefaultRedisScript<>(src, Long.class);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
