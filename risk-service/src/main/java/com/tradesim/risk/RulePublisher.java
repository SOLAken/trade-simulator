package com.tradesim.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.mq.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Nacos → Kafka 规则桥：risk-rules.yaml 变更（EnvironmentChangeEvent）后把最新阈值写入 compact topic risk.rules，
 * Flink 作业以广播流消费，无需重启作业即可生效。启动时也发一次，保证 topic 里总有一份当前规则。
 */
@Configuration
public class RulePublisher {
    private static final Logger log = LoggerFactory.getLogger(RulePublisher.class);
    private final KafkaTemplate<String, String> kafka;
    private final RiskRules rules;
    private final ObjectMapper om;

    public RulePublisher(KafkaTemplate<String, String> kafka, RiskRules rules, ObjectMapper om) {
        this.kafka = kafka;
        this.rules = rules;
        this.om = om;
    }

    /** compact：只保留每个 key 的最新值，作业从头读也只拿到最新规则。 */
    @Bean
    NewTopic riskRulesTopic() {
        return TopicBuilder.name(Topics.RISK_RULES).partitions(1).replicas(1).compact().build();
    }

    @Bean
    NewTopic riskAlertTopic() {
        return TopicBuilder.name(Topics.RISK_ALERT).partitions(4).replicas(1).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        publish();
    }

    @EventListener
    public void onChange(EnvironmentChangeEvent e) {
        if (e.getKeys().stream().anyMatch(k -> k.startsWith("risk."))) publish();
    }

    void publish() {
        try {
            String json = om.writeValueAsString(rules);
            kafka.send(Topics.RISK_RULES, "current", json);
            log.info("risk rules published: {}", json);
        } catch (Exception ex) {
            log.error("publish risk rules failed", ex);
        }
    }
}
