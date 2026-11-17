package com.tradesim.trade.engine;

import com.tradesim.common.mq.Topics;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.trade.config.TradeConfig.NodeId;
import com.tradesim.trade.ledger.OrderLedger;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 收盘撤单。归属本节点的走引擎（保证与成交串行），否则直接走账本。账本层 CAS 保证重复/并发撤销无副作用。 */
@Component
@RocketMQMessageListener(topic = Topics.RMQ_ORDER_EXPIRE, consumerGroup = "trade-expire")
public class ExpireConsumer implements RocketMQListener<EngineRouter.Expire> {
    private static final String REASON = "收盘未成交，自动撤销";
    private final MatchingEngine engine;
    private final OrderLedger ledger;
    private final int node;

    public ExpireConsumer(MatchingEngine engine, OrderLedger ledger, NodeId node) {
        this.engine = engine;
        this.ledger = ledger;
        this.node = node.value();
    }

    @Override
    public void onMessage(EngineRouter.Expire m) {
        try {
            if (m.node() == node && engine.cancel(m.symbol(), m.orderId(), OrderStatus.CANCELED, REASON)
                    .get(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (Exception ignored) {
            // 超时则落到账本兜底
        }
        ledger.cancel(m.orderId(), OrderStatus.CANCELED, REASON);
    }
}
