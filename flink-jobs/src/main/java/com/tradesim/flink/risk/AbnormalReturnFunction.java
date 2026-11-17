package com.tradesim.flink.risk;

import com.tradesim.flink.model.Events.Alert;
import com.tradesim.flink.model.Events.RankUpdate;
import com.tradesim.flink.model.Events.RiskRules;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 异常收益：同一比赛内（keyBy contestId）用 z-score 衡量某人收益偏离全体的程度。
 * 均值/方差用 Σx、Σx² 增量维护——每条更新 O(1)，不随参赛人数增长。
 * 同一比赛初始资金相同，直接用盈亏额（pnl）计算，与收益率的 z-score 等价。
 * <p>
 * 采用 leave-one-out：拿「其他所有人」的均值/标准差衡量本人。若把本人也算进样本，n 人时 z 的上限只有
 * (n-1)/√n（5 人时 1.79），阈值 4 永远触发不了——离群点会把自己所在样本的标准差撑大。
 */
public class AbnormalReturnFunction extends KeyedBroadcastProcessFunction<Long, RankUpdate, RiskRules, Alert> {
    private static final long COOLDOWN_MS = 5 * 60_000;

    private transient MapState<Long, Long> pnls;
    private transient MapState<Long, Long> lastAlert;
    private transient ValueState<double[]> moments;    // [n, Σx, Σx²]

    @Override
    public void open(Configuration parameters) {
        pnls = getRuntimeContext().getMapState(new MapStateDescriptor<>("pnl", Types.LONG, Types.LONG));
        lastAlert = getRuntimeContext().getMapState(new MapStateDescriptor<>("alerted", Types.LONG, Types.LONG));
        moments = getRuntimeContext().getState(new ValueStateDescriptor<>("moments",
                PrimitiveArrayTypeInfo.DOUBLE_PRIMITIVE_ARRAY_TYPE_INFO));
    }

    @Override
    public void processElement(RankUpdate u, ReadOnlyContext ctx, Collector<Alert> out) throws Exception {
        RiskRules rules = RuleState.get(ctx.getBroadcastState(RuleState.DESC));
        double[] m = moments.value();
        if (m == null) m = new double[3];
        Long old = pnls.get(u.userId);
        if (old != null) {
            m[0]--; m[1] -= old; m[2] -= (double) old * old;
        }
        m[0]++; m[1] += u.pnl; m[2] += (double) u.pnl * u.pnl;
        pnls.put(u.userId, u.pnl);
        moments.update(m);

        double z = zscoreAgainstOthers(m, u.pnl, rules.minStdCents);
        if (m[0] - 1 >= rules.minPeersForZscore && z > rules.abnormalReturnZscore) {
            long now = ctx.timerService().currentProcessingTime();
            Long last = lastAlert.get(u.userId);
            if (last == null || now - last >= COOLDOWN_MS) {
                lastAlert.put(u.userId, now);
                out.collect(new Alert(u.userId, u.contestId, "ABNORMAL_RETURN",
                        String.format("盈亏 %.2f 元，偏离其余 %d 人均值 %.1f 个标准差", u.pnl / 100.0, (int) m[0] - 1, z), now));
            }
        }
    }

    /**
     * @param m      含 x 在内的全体矩 [n, Σx, Σx²]
     * @param minStd 标准差下限：开赛初期多数人未交易时 std≈0，任何盈利都会被放大成极端 z 值
     * @return x 相对于其余 n-1 人的 z-score；其余人数不足 2 或标准差为 0 时返回 0
     */
    static double zscoreAgainstOthers(double[] m, long x, long minStd) {
        double n = m[0] - 1, sum = m[1] - x, sumSq = m[2] - (double) x * x;
        if (n < 2) return 0;
        double mean = sum / n;
        double var = Math.max(0, (sumSq - n * mean * mean) / (n - 1));
        double std = Math.sqrt(var);
        return (x - mean) / Math.max(std, minStd);
    }

    @Override
    public void processBroadcastElement(RiskRules rules, Context ctx, Collector<Alert> out) throws Exception {
        ctx.getBroadcastState(RuleState.DESC).put(RuleState.KEY, rules);
    }
}
