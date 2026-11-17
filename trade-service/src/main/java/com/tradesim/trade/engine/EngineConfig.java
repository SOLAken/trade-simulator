package com.tradesim.trade.engine;

import com.tradesim.common.rule.TradeRules;
import com.tradesim.trade.ledger.OrderLedger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {
    @Bean(destroyMethod = "close")
    SettlementLanes settlementLanes(@Value("${engine.settle-lanes:8}") int n, OrderLedger ledger) {
        return new SettlementLanes(n, ledger);
    }

    /** 滑点从 TradeRules 实时读取，Nacos 改了下一笔成交即生效。 */
    @Bean(destroyMethod = "close")
    MatchingEngine matchingEngine(@Value("${engine.shards:4}") int shards,
                                  @Value("${engine.ring-size:16384}") int ringSize,
                                  TradeRules rules, SettlementLanes lanes) {
        return new MatchingEngine(shards, ringSize, rules::getSlippageBps, lanes);
    }
}
