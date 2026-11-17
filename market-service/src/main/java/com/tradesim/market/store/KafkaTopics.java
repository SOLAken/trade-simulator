package com.tradesim.market.store;

import com.tradesim.common.mq.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {
    /** 8 分区：撮合引擎按分区并行消费，单分区内同一 symbol 有序。 */
    @Bean
    NewTopic marketTick() {
        return TopicBuilder.name(Topics.MARKET_TICK).partitions(8).replicas(1)
                .config("retention.ms", String.valueOf(24 * 3600 * 1000L)).build();
    }
}
