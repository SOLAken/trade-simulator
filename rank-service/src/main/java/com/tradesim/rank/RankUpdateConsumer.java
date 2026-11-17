package com.tradesim.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.contest.ContestKeys;
import com.tradesim.common.mq.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flink 实时盈亏 → ZSet。总资产 = 初始资金 + pnl。
 * 批量消费 + 去重：同一批次里同一用户只写最后一条，开盘行情剧烈时显著减少 Redis 写。
 */
@Component
public class RankUpdateConsumer {
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final RankWsHandler ws;
    private final Map<Long, Long> initCash = new ConcurrentHashMap<>();

    public RankUpdateConsumer(StringRedisTemplate redis, ObjectMapper om, RankWsHandler ws) {
        this.redis = redis;
        this.om = om;
        this.ws = ws;
    }

    @Bean
    NewTopic rankUpdateTopic() {
        return TopicBuilder.name(Topics.RANK_UPDATE).partitions(4).replicas(1).build();
    }

    @KafkaListener(topics = Topics.RANK_UPDATE, groupId = "rank-service", batch = "true")
    public void onBatch(java.util.List<String> batch) throws Exception {
        Map<String, Double> latest = new java.util.LinkedHashMap<>();   // "cid:uid" → 总资产
        for (String json : batch) {
            JsonNode u = om.readTree(json);
            long cid = u.path("contestId").asLong(), uid = u.path("userId").asLong();
            Long init = init(cid);
            if (init == null) continue;                                   // 比赛已结束/不存在：榜单冻结
            latest.put(cid + ":" + uid, (double) (init + u.path("pnl").asLong()));
        }
        latest.forEach((k, total) -> {
            int i = k.indexOf(':');
            redis.opsForZSet().add(ContestKeys.rank(Long.parseLong(k.substring(0, i))), k.substring(i + 1), total);
        });
        latest.keySet().stream().map(k -> Long.parseLong(k.substring(0, k.indexOf(':'))))
                .distinct().forEach(ws::markDirty);
    }

    private Long init(long cid) {
        Set<String> active = redis.opsForSet().members(ContestKeys.ACTIVE);
        if (active == null || !active.contains(Long.toString(cid))) return null;
        return initCash.computeIfAbsent(cid, c -> {
            Object v = redis.opsForHash().get(ContestKeys.meta(c), "initCash");
            return v == null ? null : Long.parseLong(v.toString());
        });
    }
}
