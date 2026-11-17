package com.tradesim.trade.api;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.tradesim.common.contest.ContestKeys;
import com.tradesim.common.id.GeneSnowflake;
import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.common.web.BizException;
import com.tradesim.common.web.ErrorCode;
import com.tradesim.trade.domain.OrderMapper;
import com.tradesim.trade.domain.OrderPO;
import com.tradesim.trade.exec.OrderRouter;
import com.tradesim.trade.ledger.FillCalc;
import com.tradesim.trade.ledger.OrderLedger;
import com.tradesim.trade.ledger.QuoteReader;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.tradesim.trade.config.TradeConfig.NodeId;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {
    private final OrderMapper orders;
    private final OrderLedger ledger;
    private final OrderRouter router;
    private final QuoteReader quotes;
    private final TradeRules rules;
    private final GeneSnowflake ids;
    private final StringRedisTemplate redis;
    private final int node;

    public OrderService(OrderMapper orders, OrderLedger ledger, OrderRouter router, QuoteReader quotes,
                        TradeRules rules, GeneSnowflake ids, StringRedisTemplate redis, NodeId node) {
        this.node = node.value();
        this.orders = orders;
        this.ledger = ledger;
        this.router = router;
        this.quotes = quotes;
        this.rules = rules;
        this.ids = ids;
        this.redis = redis;
    }

    public record PlaceReq(long contestId, String symbol, int side, int type, long qty, long price, long stopPrice) {}

    /**
     * 下单。幂等两道闸：
     * 1) Redis SETNX —— 挡住并发重复提交（双击、网关重试），后到者等前者结果；
     * 2) 唯一键 (user_id, client_key) —— 最终防线，Redis 失效也不会重复下单。
     * <p>
     * Sentinel 热点参数限流：按 userId（参数 0）单用户每秒 5 单，规则在 Nacos，防脚本刷单。
     */
    @SentinelResource(value = "placeOrder", blockHandler = "placeBlocked")
    public OrderPO place(long userId, String clientKey, PlaceReq req) {
        String idemKey = "idem:{" + userId + "}:" + clientKey;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofHours(24)))) {
            OrderPO existing = orders.findByClientKey(userId, clientKey);
            if (existing != null) return existing;
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);   // 前一个请求仍在处理中
        }
        try {
            OrderPO o = build(userId, clientKey, req);
            ledger.createAndFreeze(o);
            try {
                // 等引擎处理完首个事件：市价单/可立即成交的限价单返回成交后状态，其余返回挂单状态
                router.accept(o).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 超时不影响下单结果，前端轮询/推送获取后续状态
            }
            return orders.selectById(o.id);
        } catch (DuplicateKeyException e) {
            return orders.findByClientKey(userId, clientKey);
        } catch (RuntimeException e) {
            redis.delete(idemKey);       // 失败（如资金不足）允许用同一个 key 重试
            throw e;
        }
    }

    public OrderPO placeBlocked(long userId, String clientKey, PlaceReq req, BlockException e) {
        throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
    }

    public boolean cancel(long userId, long orderId) {
        OrderPO o = orders.selectById(orderId);
        if (o == null || o.userId != userId) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        if (OrderStatus.of(o.status).isFinal()) return false;
        return router.cancel(o);
    }

    private OrderPO build(long userId, String clientKey, PlaceReq req) {
        long now = System.currentTimeMillis();
        if (!rules.clock().isOpen(now)) throw new BizException(ErrorCode.MARKET_CLOSED);
        checkContestWindow(req.contestId(), now);
        Side side = Side.of(req.side());
        OrderType type = OrderType.of(req.type());
        if (req.qty() <= 0 || (side == Side.BUY && req.qty() % 100 != 0)) {
            throw new IllegalArgumentException("买入数量须为 100 股的整数倍");
        }
        MarketTick q = quotes.latest(req.symbol());
        if (q == null) throw new BizException(ErrorCode.NO_QUOTE);
        if (type == OrderType.LIMIT && (req.price() < q.limitDown() || req.price() > q.limitUp())) {
            throw new BizException(ErrorCode.PRICE_OUT_OF_LIMIT);
        }
        if (type == OrderType.STOP && req.stopPrice() <= 0) throw new IllegalArgumentException("止损单须指定触发价");

        OrderPO o = new OrderPO();
        o.id = ids.nextId(userId);
        o.userId = userId;
        o.contestId = req.contestId();
        o.clientKey = clientKey;
        o.symbol = req.symbol();
        o.side = side.code;
        o.type = type.code;
        o.price = type == OrderType.LIMIT ? req.price() : 0L;
        o.stopPrice = type == OrderType.STOP ? req.stopPrice() : 0L;
        o.qty = req.qty();
        o.filledQty = 0L;
        o.filledAmount = 0L;
        o.used = 0L;
        o.status = OrderStatus.NEW.code;
        o.tradingDay = rules.clock().tradingDay(now);
        o.node = node;
        // 买单：限价按限价冻结；市价/止损按涨停价冻结（最坏情况），成交后多退
        o.frozen = side == Side.BUY
                ? FillCalc.buyFreeze(type == OrderType.LIMIT ? req.price() : q.limitUp(), req.qty(), rules)
                : req.qty();
        return o;
    }

    private void checkContestWindow(long contestId, long now) {
        var meta = redis.opsForHash().multiGet(ContestKeys.meta(contestId), java.util.List.of("startAt", "endAt"));
        if (meta.get(0) == null) throw new BizException(ErrorCode.CONTEST_NOT_FOUND);
        if (now < Long.parseLong((String) meta.get(0)) || now >= Long.parseLong((String) meta.get(1))) {
            throw new BizException(ErrorCode.CONTEST_CLOSED);
        }
    }
}
