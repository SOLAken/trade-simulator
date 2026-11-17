package com.tradesim.trade.ledger;

import com.tradesim.common.fund.FundStore;
import com.tradesim.common.id.GeneSnowflake;
import com.tradesim.common.mq.Topics;
import com.tradesim.common.order.*;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.common.web.BizException;
import com.tradesim.common.web.ErrorCode;
import com.tradesim.trade.domain.*;
import com.tradesim.trade.mq.EventPublisher;
import com.tradesim.trade.mq.TxSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单账本：下单冻结 / 成交结算 / 撤单释放，三种状态变更各对应一条 RocketMQ 事务消息。
 *
 * <pre>
 *            Redis(热数据)                MySQL(订单)            RocketMQ → account-service(账本)
 * 冻结  : Lua 冻结【提交前，做校验】   插入订单             FREEZE
 * 结算  : Lua 结算【提交后，幂等重试】 CAS 更新订单+写成交   SETTLE
 * 释放  : Lua 释放【提交后，幂等重试】 CAS 关闭订单         RELEASE
 * </pre>
 * 「校验型」操作必须在提交前（不足要拒单），「结果型」操作放提交后（DB 回滚就不会产生脏 Redis），
 * 提交后 Redis 若失败，由标记位保证重试幂等，对账任务兜底。
 */
@Service
public class OrderLedger {
    private static final Logger log = LoggerFactory.getLogger(OrderLedger.class);
    private static final int MAX_CAS_RETRY = 5;

    private final OrderMapper orders;
    private final TradeMapper trades;
    private final FundStore fund;
    private final TxSender tx;
    private final TransactionTemplate txTemplate;
    private final GeneSnowflake ids;
    private final TradeRules rules;
    private final EventPublisher events;

    public OrderLedger(OrderMapper orders, TradeMapper trades, FundStore fund, TxSender tx,
                       TransactionTemplate txTemplate, GeneSnowflake ids, TradeRules rules, EventPublisher events) {
        this.orders = orders;
        this.trades = trades;
        this.fund = fund;
        this.tx = tx;
        this.txTemplate = txTemplate;
        this.ids = ids;
        this.rules = rules;
        this.events = events;
    }

    // ------------------------------------------------------------------ 冻结

    /** 插入订单并冻结资金/持仓，同时发送 FREEZE 事务消息。资金不足抛 BizException。 */
    public void createAndFreeze(OrderPO o) {
        var body = new FundMessages.Freeze(o.id, o.userId, o.contestId, o.symbol, o.side, o.frozen, o.tradingDay);
        tx.send(Topics.RMQ_ORDER_FREEZE, "FREEZE", o.id, body, () -> txTemplate.executeWithoutResult(s -> {
            orders.insert(o);
            int r = o.side == Side.BUY.code
                    ? fund.freezeCash(o.userId, o.contestId, o.id, o.frozen)
                    : fund.freezePosition(o.userId, o.contestId, o.symbol, o.id, o.frozen, o.tradingDay);
            switch (r) {
                case FundStore.INSUFFICIENT -> throw new BizException(
                        o.side == Side.BUY.code ? ErrorCode.INSUFFICIENT_FUNDS : ErrorCode.INSUFFICIENT_POSITION);
                case FundStore.NO_ACCOUNT -> throw new BizException(ErrorCode.NO_ACCOUNT);
                default -> { }
            }
            // Redis 已冻结；若随后 DB 提交失败，补偿解冻
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED && r == FundStore.OK) {
                        fund.undoFreeze(o.userId, o.contestId, o.symbol, o.id, o.side, o.frozen);
                    }
                }
            });
        }));
        events.order(event(o));
    }

    // ------------------------------------------------------------------ 结算

    /**
     * 结算一笔成交。
     * @param closeRest true 表示这笔成交后订单剩余部分直接终结（市价单 IOC）
     * @return 结算后的订单；订单已终结则返回 null
     */
    public OrderPO settle(long orderId, long price, long qty, boolean closeRest) {
        for (int attempt = 0; attempt < MAX_CAS_RETRY; attempt++) {
            OrderPO o = orders.selectById(orderId);
            if (o == null || OrderStatus.of(o.status).isFinal()) return null;
            long fillQty = Math.min(qty, o.qty - o.filledQty);
            Side side = Side.of(o.side);
            FillCalc c = FillCalc.of(side, price, fillQty, rules);
            long newFilled = o.filledQty + fillQty;
            boolean fullyFilled = newFilled == o.qty;
            boolean done = fullyFilled || closeRest;
            // 订单终结时退回剩余冻结；可能为负（手续费率在冻结后被调高），负数即从可用资金补扣
            long release = done ? o.frozen - o.used - c.used() : 0;
            OrderStatus status = fullyFilled ? OrderStatus.FILLED : closeRest ? OrderStatus.CANCELED : OrderStatus.PARTIAL;
            long tradeId = ids.nextId(o.userId);
            int day = rules.clock().tradingDay(System.currentTimeMillis());

            var body = new FundMessages.Settle(tradeId, o.id, o.userId, o.contestId, o.symbol, o.side,
                    price, fillQty, c.amount(), c.commission(), c.tax(), release, day);
            try {
                tx.send(Topics.RMQ_TRADE_SETTLE, "SETTLE", tradeId, body, () -> txTemplate.executeWithoutResult(s -> {
                    if (orders.applyFill(o.id, o.userId, o.filledQty, fillQty, c.amount(), c.used(), status.code) == 0) {
                        throw new CasConflict();
                    }
                    trades.insert(trade(tradeId, o, price, fillQty, c, day));
                    afterCommit(() -> fund.settle(o.userId, o.contestId, o.symbol, tradeId, o.side, fillQty,
                            c.amount(), c.fee(), release, day));
                }));
            } catch (CasConflict e) {
                log.debug("settle CAS conflict order={} attempt={}", orderId, attempt);
                continue;
            }
            o.filledQty = newFilled;
            o.status = status.code;
            events.trade(new OrderEvent.Trade(tradeId, o.id, o.userId, o.contestId, o.symbol, o.side,
                    price, fillQty, c.amount(), c.fee(), System.currentTimeMillis()));
            events.order(event(o));
            return o;
        }
        throw new IllegalStateException("settle CAS retry exhausted, order=" + orderId);
    }

    // ------------------------------------------------------------------ 撤单 / 过期

    /** @return true 本次撤成；false 订单已终结 */
    public boolean cancel(long orderId, OrderStatus finalStatus, String reason) {
        for (int attempt = 0; attempt < MAX_CAS_RETRY; attempt++) {
            OrderPO o = orders.selectById(orderId);
            if (o == null || OrderStatus.of(o.status).isFinal()) return false;
            long release = o.frozen - o.used;
            int day = rules.clock().tradingDay(System.currentTimeMillis());
            var body = new FundMessages.Release(o.id, o.userId, o.contestId, o.symbol, o.side, release, finalStatus.code);
            try {
                tx.send(Topics.RMQ_ORDER_UNFREEZE, "RELEASE", o.id, body, () -> txTemplate.executeWithoutResult(s -> {
                    if (orders.close(o.id, o.userId, o.filledQty, finalStatus.code, reason) == 0) throw new CasConflict();
                    afterCommit(() -> fund.release(o.userId, o.contestId, o.symbol, o.id, o.side, release, day));
                }));
            } catch (CasConflict e) {
                continue;
            }
            o.status = finalStatus.code;
            events.order(event(o));
            return true;
        }
        throw new IllegalStateException("cancel CAS retry exhausted, order=" + orderId);
    }

    // ------------------------------------------------------------------

    private static void afterCommit(Runnable r) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 失败不抛出：DB 与消息已提交，Redis 由幂等标记 + 对账任务修复
                for (int i = 0; i < 3; i++) {
                    try {
                        r.run();
                        return;
                    } catch (Exception e) {
                        log.error("redis post-commit apply failed, attempt {}", i + 1, e);
                    }
                }
            }
        });
    }

    private static TradePO trade(long id, OrderPO o, long price, long qty, FillCalc c, int day) {
        TradePO t = new TradePO();
        t.id = id;
        t.orderId = o.id;
        t.userId = o.userId;
        t.contestId = o.contestId;
        t.symbol = o.symbol;
        t.side = o.side;
        t.price = price;
        t.qty = qty;
        t.amount = c.amount();
        t.commission = c.commission();
        t.tax = c.tax();
        t.tradingDay = day;
        return t;
    }

    static OrderEvent.Order event(OrderPO o) {
        return new OrderEvent.Order(o.id, o.userId, o.contestId, o.symbol, o.side, o.type, o.status,
                o.price, o.qty, o.filledQty, System.currentTimeMillis());
    }

    /** CAS 失败：订单在读取后被其他线程修改，回滚本地事务与半消息后重算。 */
    static final class CasConflict extends RuntimeException {
        CasConflict() {
            super(null, null, false, false);
        }
    }
}
