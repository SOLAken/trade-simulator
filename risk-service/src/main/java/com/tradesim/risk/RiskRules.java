package com.tradesim.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Nacos risk-rules.yaml。字段名与 Flink 侧 Events.RiskRules 一致，直接 JSON 广播。 */
@ConfigurationProperties(prefix = "risk")
public class RiskRules {
    private int maxOrdersPer10s = 20;
    private int quickCancelMillis = 3000;
    private int maxQuickCancelsPer30s = 8;
    private double abnormalReturnZscore = 4.0;
    private int minPeersForZscore = 5;
    private long minStdCents = 1_000_000;
    private int blockMinutes = 10;

    public int getMaxOrdersPer10s() { return maxOrdersPer10s; }
    public void setMaxOrdersPer10s(int v) { maxOrdersPer10s = v; }
    public int getQuickCancelMillis() { return quickCancelMillis; }
    public void setQuickCancelMillis(int v) { quickCancelMillis = v; }
    public int getMaxQuickCancelsPer30s() { return maxQuickCancelsPer30s; }
    public void setMaxQuickCancelsPer30s(int v) { maxQuickCancelsPer30s = v; }
    public double getAbnormalReturnZscore() { return abnormalReturnZscore; }
    public void setAbnormalReturnZscore(double v) { abnormalReturnZscore = v; }
    public int getMinPeersForZscore() { return minPeersForZscore; }
    public void setMinPeersForZscore(int v) { minPeersForZscore = v; }
    public long getMinStdCents() { return minStdCents; }
    public void setMinStdCents(long v) { minStdCents = v; }
    public int getBlockMinutes() { return blockMinutes; }
    public void setBlockMinutes(int v) { blockMinutes = v; }
}
