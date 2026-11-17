package com.tradesim.common.fund;

/**
 * 资金/持仓热数据的 Redis key。{uid} 为 hash tag：同一用户的所有 key 落在同一个 Cluster slot，
 * 多 key 的 Lua 脚本在集群模式下依然合法。
 */
public final class FundKeys {
    private FundKeys() {}

    /** hash: cash 可用资金(分), frozen 冻结资金(分) */
    public static String account(long uid, long contestId) { return "acct:{" + uid + "}:" + contestId; }

    /** hash: qty 总持仓, avail 可卖, frozen 卖出冻结, pending 今日买入(T+1 解锁), day 最近交易日, cost 持仓成本(分) */
    public static String position(long uid, long contestId, String symbol) { return "pos:{" + uid + "}:" + contestId + ":" + symbol; }

    /** set: 用户在该比赛中持有/持有过的股票 */
    public static String positionSet(long uid, long contestId) { return "posset:{" + uid + "}:" + contestId; }

    /** 幂等标记，string，带过期 */
    public static String marker(String kind, long uid, long bizId) { return kind + ":{" + uid + "}:" + bizId; }
}
