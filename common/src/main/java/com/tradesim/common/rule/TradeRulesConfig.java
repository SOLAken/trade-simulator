package com.tradesim.common.rule;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 TradeRules。Spring Cloud 的 ConfigurationPropertiesRebinder 会在 Nacos 推送变更后
 * 重新绑定 @ConfigurationProperties Bean，因此费率等字段无需 @RefreshScope 即可热更新。
 */
@AutoConfiguration
public class TradeRulesConfig {
    @Bean
    @ConfigurationProperties(prefix = "trade")
    public TradeRules tradeRules() {
        return new TradeRules();
    }
}
