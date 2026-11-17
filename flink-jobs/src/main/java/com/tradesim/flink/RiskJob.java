package com.tradesim.flink;

import com.tradesim.flink.model.Events.*;
import com.tradesim.flink.risk.AbnormalReturnFunction;
import com.tradesim.flink.risk.OrderBehaviorFunction;
import com.tradesim.flink.risk.RuleState;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * 实时风控：
 *  order.event ─keyBy(userId)──► 高频下单 / 挂撤刷单 ─┐
 *  rank.update ─keyBy(contest)─► 异常收益 z-score ────┼─► risk.alert
 *  risk.rules  ─broadcast────────(阈值热更新)─────────┘
 */
public class RiskJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = Sources.env();
        // 规则 topic 是 compact 的，每次从最早读，保证启动即拿到最新阈值
        BroadcastStream<RiskRules> rules = Sources.json(env, "risk.rules", "flink-risk-rules-" + System.nanoTime(), true, RiskRules.class)
                .broadcast(RuleState.DESC);

        DataStream<Alert> behavior = Sources.json(env, "order.event", "flink-risk-order", false, Order.class)
                .keyBy(o -> o.userId)
                .connect(rules)
                .process(new OrderBehaviorFunction()).name("order-behavior").uid("order-behavior");

        DataStream<Alert> returns = Sources.json(env, "rank.update", "flink-risk-return", false, RankUpdate.class)
                .keyBy(u -> u.contestId)
                .connect(rules)
                .process(new AbnormalReturnFunction()).name("abnormal-return").uid("abnormal-return");

        behavior.union(returns)
                .sinkTo(Sources.<Alert>sink("risk.alert", a -> Long.toString(a.userId))).name("risk-alert");
        env.execute("tradesim-risk");
    }
}
