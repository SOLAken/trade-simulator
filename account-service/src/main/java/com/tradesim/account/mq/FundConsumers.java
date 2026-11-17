package com.tradesim.account.mq;

import com.tradesim.common.mq.Topics;
import com.tradesim.common.order.FundMessages;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** 三类资金消息各一个消费组；抛异常即由 RocketMQ 重试（默认 16 次，之后进死信队列）。 */
public final class FundConsumers {
    private FundConsumers() {}

    @Component
    @RocketMQMessageListener(topic = Topics.RMQ_ORDER_FREEZE, consumerGroup = "account-freeze")
    public static class Freeze implements RocketMQListener<FundMessages.Freeze> {
        private final LedgerService ledger;
        public Freeze(LedgerService ledger) { this.ledger = ledger; }
        @Override public void onMessage(FundMessages.Freeze m) { ledger.freeze(m); }
    }

    @Component
    @RocketMQMessageListener(topic = Topics.RMQ_TRADE_SETTLE, consumerGroup = "account-settle")
    public static class Settle implements RocketMQListener<FundMessages.Settle> {
        private final LedgerService ledger;
        public Settle(LedgerService ledger) { this.ledger = ledger; }
        @Override public void onMessage(FundMessages.Settle m) { ledger.settle(m); }
    }

    @Component
    @RocketMQMessageListener(topic = Topics.RMQ_ORDER_UNFREEZE, consumerGroup = "account-release")
    public static class Release implements RocketMQListener<FundMessages.Release> {
        private final LedgerService ledger;
        public Release(LedgerService ledger) { this.ledger = ledger; }
        @Override public void onMessage(FundMessages.Release m) { ledger.release(m); }
    }
}
