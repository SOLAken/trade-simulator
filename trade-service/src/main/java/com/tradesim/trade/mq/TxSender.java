package com.tradesim.trade.mq;

import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 事务消息发送：半消息 → 本地事务 → COMMIT/ROLLBACK；Broker 未收到结果时回查 {@link TxListener}。
 * 本地事务抛出的业务异常会原样抛给调用方。
 */
@Component
public class TxSender {
    public static final String BIZ = "biz";
    public static final String BIZ_ID = "bizId";

    private final RocketMQTemplate rocket;

    public TxSender(RocketMQTemplate rocket) {
        this.rocket = rocket;
    }

    public void send(String topic, String biz, long bizId, Object payload, Runnable localTx) {
        var msg = MessageBuilder.withPayload(payload)
                .setHeader(BIZ, biz)
                .setHeader(BIZ_ID, Long.toString(bizId))
                .setHeader("KEYS", Long.toString(bizId))       // 控制台按 key 查消息
                .build();
        TxAction action = new TxAction(localTx);
        TransactionSendResult r = rocket.sendMessageInTransaction(topic, msg, action);
        if (action.error() != null) throw action.error();
        if (r.getLocalTransactionState() != LocalTransactionState.COMMIT_MESSAGE) {
            throw new IllegalStateException("tx message not committed: " + r.getLocalTransactionState());
        }
    }
}
