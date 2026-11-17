package com.tradesim.common.order;

/**
 * trade → account 的 RocketMQ 事务消息体。全部为「增量」语义：
 * account-service 按消息直接加减余额，不做余额校验（校验已在 Redis 前置完成），
 * 因此消息乱序到达也能收敛到同一终态（加法交换律），无需顺序消息。
 * 幂等靠流水表唯一键 (biz_id, type)。
 */
public final class FundMessages {
    private FundMessages() {}

    /** 下单冻结。买单 amount=冻结资金(分)，卖单 amount=冻结股数。 */
    public record Freeze(long orderId, long userId, long contestId, String symbol, int side, long amount, int tradingDay) {}

    /** 成交结算。release>0 表示订单已终结，同时退回剩余冻结（买=分，卖=股）。 */
    public record Settle(long tradeId, long orderId, long userId, long contestId, String symbol, int side,
                         long price, long qty, long amount, long commission, long tax, long release, int tradingDay) {}

    /** 撤单/过期退回剩余冻结。 */
    public record Release(long orderId, long userId, long contestId, String symbol, int side, long release, int reason) {}
}
