package com.tradesim.trade.mq;

import com.tradesim.common.mq.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {
    @Bean
    NewTopic orderEvent() {
        return TopicBuilder.name(Topics.ORDER_EVENT).partitions(8).replicas(1).build();
    }

    /** 成交事件保留 30 天：Flink 收益作业首次启动从最早位点重放，重建全部持仓。 */
    @Bean
    NewTopic tradeEvent() {
        return TopicBuilder.name(Topics.TRADE_EVENT).partitions(8).replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 3600 * 1000)).build();
    }
}
