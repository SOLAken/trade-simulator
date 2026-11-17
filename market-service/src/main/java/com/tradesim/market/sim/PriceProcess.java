package com.tradesim.market.sim;

import java.util.random.RandomGenerator;

/**
 * 单只股票的价格过程：几何布朗运动 + 泊松跳变（Merton jump-diffusion 的离散近似）。
 * <p>
 * 内部维护一个不受涨跌停约束的「理论价」fair，对外发布的成交价 = clamp(fair, 跌停, 涨停)。
 * 这样封板后理论价仍在游走，开板时会自然回落/反弹，而不是一直贴着板。
 * 纯函数式计算、无 IO，便于单测与确定性回放（传入固定种子的 RandomGenerator）。
 */
public final class PriceProcess {
    private static final int TRADING_DAYS_PER_YEAR = 250;

    private final double mu;
    private final double sigma;
    private final double jumpProb;
    private final double jumpSigma;
    private final RandomGenerator rnd;
    private double fair;

    public PriceProcess(double startPrice, double mu, double sigma, double jumpProb, double jumpSigma, RandomGenerator rnd) {
        this.fair = startPrice;
        this.mu = mu;
        this.sigma = sigma;
        this.jumpProb = jumpProb;
        this.jumpSigma = jumpSigma;
        this.rnd = rnd;
    }

    /**
     * @param dtDays 本步长占一个交易日的比例（tickMillis / dayMillis）
     * @return 新的理论价
     */
    public double step(double dtDays) {
        double dt = dtDays / TRADING_DAYS_PER_YEAR;
        double logRet = (mu - 0.5 * sigma * sigma) * dt + sigma * Math.sqrt(dt) * rnd.nextGaussian();
        if (rnd.nextDouble() < jumpProb) {
            logRet += rnd.nextGaussian() * jumpSigma;   // 新闻事件
        }
        fair *= Math.exp(logRet);
        return fair;
    }

    /** 新交易日开盘：用跳空模拟隔夜消息，并把理论价拉回到昨收附近避免长期贴板。 */
    public double openGap(double prevClose) {
        fair = prevClose * Math.exp(rnd.nextGaussian() * sigma / Math.sqrt(TRADING_DAYS_PER_YEAR) * 0.3);
        return fair;
    }

    public double fair() { return fair; }
}
