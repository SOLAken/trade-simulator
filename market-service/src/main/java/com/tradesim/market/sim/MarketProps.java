package com.tradesim.market.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "market")
public record MarketProps(long tickMillis, List<SymbolDef> symbols, double jumpProb, double jumpSigma) {
    public record SymbolDef(String symbol, String name, BigDecimal initPrice, double sigma, double mu) {
        public long initCents() {
            return initPrice.movePointRight(2).longValueExact();
        }
    }
}
