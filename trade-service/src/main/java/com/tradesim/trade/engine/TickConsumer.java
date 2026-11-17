package com.tradesim.trade.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.market.MarketTick;
import com.tradesim.common.mq.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 行情驱动撮合。每个 trade 实例独立 consumer group（广播）：对手方是市场，
 * 每个实例只撮合自己受理的订单，因此都需要全量行情。
 */
@Component
public class TickConsumer {
    private final MatchingEngine engine;
    private final ObjectMapper om;

    public TickConsumer(MatchingEngine engine, ObjectMapper om) {
        this.engine = engine;
        this.om = om;
    }

    @KafkaListener(topics = Topics.MARKET_TICK, groupId = "trade-engine-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onTick(String json) throws Exception {
        engine.onTick(om.readValue(json, MarketTick.class));
    }
}
