package com.tradesim.common.contest;

/** 比赛相关 Redis key（user-service 写，trade / rank 读）。 */
public final class ContestKeys {
    private ContestKeys() {}

    /** hash: name, initCash, startAt, endAt（毫秒） */
    public static String meta(long contestId) { return "contest:" + contestId; }

    /** set: 参赛 userId */
    public static String members(long contestId) { return "contest:members:" + contestId; }

    /** set: 未结束的比赛 id（排行榜任务遍历） */
    public static final String ACTIVE = "contests:active";

    /** hash: userId → 昵称 */
    public static final String NICKNAMES = "user:nick";

    /** zset: 总资产（分）排行 */
    public static String rank(long contestId) { return "rank:" + contestId; }
}
