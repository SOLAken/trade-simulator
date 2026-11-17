package com.tradesim.trade.engine;

import com.tradesim.common.mq.Topics;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.trade.config.TradeConfig.NodeId;
import com.tradesim.trade.domain.OrderPO;
import com.tradesim.trade.exec.OrderRouter;
import com.tradesim.trade.ledger.OrderLedger;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class EngineRouter implements OrderRouter {
    private static final Logger log = LoggerFactory.getLogger(EngineRouter.class);

    private final MatchingEngine engine;
    private final OrderLedger ledger;
    private final RocketMQTemplate rocket;
    private final TradeRules rules;
    private final int node;

    public EngineRouter(MatchingEngine engine, OrderLedger ledger, RocketMQTemplate rocket, TradeRules rules, NodeId node) {
        this.engine = engine;
        this.ledger = ledger;
        this.rocket = rocket;
        this.rules = rules;
        this.node = node.value();
    }

    public record Expire(long orderId, String symbol, int node) {}

    @Override
    public CompletableFuture<Boolean> accept(OrderPO o) {
        if (o.type != OrderType.MARKET.code) scheduleExpire(o);
        return engine.submit(toBook(o));
    }

    @Override
    public boolean cancel(OrderPO o) {
        try {
            if (o.node == node && engine.cancel(o.symbol, o.id, OrderStatus.CANCELED, "用户撤单").get(3, TimeUnit.SECONDS)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("engine cancel timeout order={}", o.id);
        }
        // 不在本节点的簿中（归属节点宕机/已重启丢失）：直接在账本层撤销，引擎侧后续成交会因订单已终结被忽略
        return ledger.cancel(o.id, OrderStatus.CANCELED, "用户撤单");
    }

    /**
     * RocketMQ 5 定时消息：投递时间 = 当日收盘时刻。到点由 {@link ExpireConsumer} 撤销未成交部分并解冻。
     * 相比扫表：不轮询数据库，订单量再大也只是消息堆积在 Broker 的时间轮里。
     */
    private void scheduleExpire(OrderPO o) {
        long closeAt = rules.clock().closeAt(System.currentTimeMillis());
        try {
            rocket.syncSendDeliverTimeMills(Topics.RMQ_ORDER_EXPIRE,
                    MessageBuilder.withPayload(new Expire(o.id, o.symbol, o.node)).setHeader("KEYS", o.id).build(), closeAt);
        } catch (Exception e) {
            log.warn("schedule expire failed order={}, rely on engine day-roll", o.id, e);
        }
    }

    static BookOrder toBook(OrderPO o) {
        return new BookOrder(o.id, o.userId, o.symbol, Side.of(o.side), OrderType.of(o.type),
                o.price, o.stopPrice, o.qty - o.filledQty, o.tradingDay);
    }
}
