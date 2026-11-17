package com.tradesim.trade.config;

import com.tradesim.common.fund.FundStore;
import com.tradesim.common.id.GeneSnowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class TradeConfig {
    private static final Logger log = LoggerFactory.getLogger(TradeConfig.class);

    @Bean
    FundStore fundStore(StringRedisTemplate redis) {
        return new FundStore(redis);
    }

    /**
     * workerId 由 Redis INCR 分配（mod 32），多实例扩容时无需手工配置。
     * 32 个实例以内不会冲突；更大规模应换成带租约的分配（如 Nacos 临时节点）。
     */
    @Bean
    GeneSnowflake idGenerator(StringRedisTemplate redis) {
        Long n = redis.opsForValue().increment("id:worker:trade");
        long worker = Math.floorMod(n == null ? 0 : n, 32L);
        log.info("snowflake workerId={}", worker);
        return new GeneSnowflake(worker);
    }
}
