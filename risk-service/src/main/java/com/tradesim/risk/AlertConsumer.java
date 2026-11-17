package com.tradesim.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.contest.RiskKeys;
import com.tradesim.common.mq.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 处置 Flink 告警：
 * - 刷单类（高频下单、挂撤刷单）→ 限时封禁下单（Redis TTL，到期自动解封）
 * - 异常收益 → 只记录待人工复核（收益高不一定作弊，不能自动处罚）
 */
@Component
public class AlertConsumer {
    private static final Logger log = LoggerFactory.getLogger(AlertConsumer.class);
    private static final Set<String> BLOCKING = Set.of("FREQUENT_ORDERS", "SPOOFING");

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final RiskRules rules;

    public AlertConsumer(StringRedisTemplate redis, ObjectMapper om, RiskRules rules) {
        this.redis = redis;
        this.om = om;
        this.rules = rules;
    }

    @KafkaListener(topics = Topics.RISK_ALERT, groupId = "risk-service")
    public void onAlert(String json) throws Exception {
        JsonNode a = om.readTree(json);
        long uid = a.path("userId").asLong();
        String type = a.path("type").asText();
        redis.opsForList().leftPush(RiskKeys.ALERTS, json);
        redis.opsForList().trim(RiskKeys.ALERTS, 0, 199);
        if (BLOCKING.contains(type)) {
            redis.opsForValue().set(RiskKeys.block(uid), type + "：" + a.path("detail").asText(),
                    Duration.ofMinutes(rules.getBlockMinutes()));
        }
        log.warn("risk alert user={} type={} detail={}", uid, type, a.path("detail").asText());
    }
}
