package com.tradesim.trade.mq;

import com.tradesim.trade.domain.OrderMapper;
import com.tradesim.trade.domain.OrderPO;
import com.tradesim.trade.domain.TradeMapper;
import com.tradesim.common.order.OrderStatus;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

/**
 * 统一事务监听器（一个 RocketMQTemplate 只能绑一个），按 biz 头分派回查逻辑。
 * 回查原则：以本地数据库是否已落下对应记录为准——记录在则 COMMIT，不在则 ROLLBACK。
 */
@RocketMQTransactionListener
public class TxListener implements RocketMQLocalTransactionListener {
    private static final Logger log = LoggerFactory.getLogger(TxListener.class);

    private final OrderMapper orders;
    private final TradeMapper trades;

    public TxListener(OrderMapper orders, TradeMapper trades) {
        this.orders = orders;
        this.trades = trades;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        return ((TxAction) arg).run() ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String biz = (String) msg.getHeaders().get(TxSender.BIZ);
        long id = Long.parseLong((String) msg.getHeaders().get(TxSender.BIZ_ID));
        boolean committed = switch (biz) {
            case "FREEZE" -> orders.selectById(id) != null;
            case "SETTLE" -> trades.selectById(id) != null;
            case "RELEASE" -> {
                OrderPO o = orders.selectById(id);
                yield o != null && (o.status == OrderStatus.CANCELED.code || o.status == OrderStatus.REJECTED.code);
            }
            default -> false;
        };
        log.warn("tx check biz={} id={} -> {}", biz, id, committed ? "COMMIT" : "ROLLBACK");
        return committed ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
    }
}
