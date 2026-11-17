package com.tradesim.trade.config;

import com.tradesim.common.fund.FundStore;
import com.tradesim.common.id.GeneSnowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class TradeConfig {
    private static final Logger log = LoggerFactory.getLogger(TradeConfig.class);
    private static final Pattern ORDINAL = Pattern.compile("-(\\d+)$");

    @Bean
    FundStore fundStore(StringRedisTemplate redis) {
        return new FundStore(redis);
    }

    /**
     * 节点号：同时作为雪花 workerId 与撮合归属节点，必须在重启后保持不变（否则无法认领自己的挂单）。
     * 优先 WORKER_ID；K8s StatefulSet 下从主机名 trade-service-N 取序号；本地默认 0。
     */
    @Bean
    NodeId nodeId(@Value("${WORKER_ID:}") String workerId, @Value("${HOSTNAME:}") String host) {
        int id = 0;
        if (!workerId.isBlank()) {
            id = Integer.parseInt(workerId);
        } else {
            Matcher m = ORDINAL.matcher(host);
            if (m.find()) id = Integer.parseInt(m.group(1));
        }
        log.info("trade node id = {}", id);
        return new NodeId(id);
    }

    @Bean
    GeneSnowflake idGenerator(NodeId node) {
        return new GeneSnowflake(node.value());
    }

    public record NodeId(int value) {}
}
