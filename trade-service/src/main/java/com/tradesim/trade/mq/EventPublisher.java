package com.tradesim.trade.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.mq.Topics;
import com.tradesim.common.order.OrderEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 订单/成交事件 → Kafka（key=userId，同一用户事件有序，便于 Flink 按用户开窗）。尽力而为，不影响主流程。 */
@Component
public class EventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper om;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper om) {
        this.kafka = kafka;
        this.om = om;
    }

    public void order(OrderEvent.Order e) {
        send(Topics.ORDER_EVENT, e.userId(), e);
    }

    public void trade(OrderEvent.Trade e) {
        send(Topics.TRADE_EVENT, e.userId(), e);
    }

    private void send(String topic, long userId, Object payload) {
        try {
            kafka.send(topic, Long.toString(userId), om.writeValueAsString(payload));
        } catch (Exception ignored) {
            // 事件流丢失只影响风控/排行实时性，对账以 MySQL 为准
        }
    }
}
