package com.tradesim.market.sim;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class PriceProcessTest {

    @Test
    void sameSeedSamePathForReplay() {
        var a = new PriceProcess(1000, 0.05, 0.4, 0.001, 0.04, new SplittableRandom(7));
        var b = new PriceProcess(1000, 0.05, 0.4, 0.001, 0.04, new SplittableRandom(7));
        for (int i = 0; i < 1000; i++) assertThat(a.step(1.0 / 3600)).isEqualTo(b.step(1.0 / 3600));
    }

    @Test
    void dailyVolatilityMatchesSigma() {
        // 无跳变时，一个交易日的对数收益率标准差应 ≈ sigma / sqrt(250)
        double sigma = 0.4;
        int days = 2000, steps = 360;
        double sum = 0, sumSq = 0;
        var rnd = new SplittableRandom(42);
        for (int d = 0; d < days; d++) {
            var p = new PriceProcess(1000, 0, sigma, 0, 0, rnd);
            double px = 1000;
            for (int i = 0; i < steps; i++) px = p.step(1.0 / steps);
            double r = Math.log(px / 1000);
            sum += r;
            sumSq += r * r;
        }
        double std = Math.sqrt(sumSq / days - Math.pow(sum / days, 2));
        assertThat(std).isCloseTo(sigma / Math.sqrt(250), org.assertj.core.data.Offset.offset(0.003));
    }
}
