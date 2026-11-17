package com.tradesim.account;

import com.tradesim.common.fund.FundStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class AccountConfig {
    @Bean
    FundStore fundStore(StringRedisTemplate redis) {
        return new FundStore(redis);
    }
}
