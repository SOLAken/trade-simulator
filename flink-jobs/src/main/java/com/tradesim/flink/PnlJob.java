package com.tradesim.flink;

import com.tradesim.flink.model.Events.RankUpdate;
import com.tradesim.flink.model.Events.Tick;
import com.tradesim.flink.model.Events.Trade;
import com.tradesim.flink.pnl.PnlFunction;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/** trade.event + market.tick(广播) → 每用户实时盈亏 → rank.update */
public class PnlJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = Sources.env();
        BroadcastStream<Tick> ticks = Sources.json(env, "market.tick", "flink-pnl-tick", false, Tick.class)
                .broadcast(PnlFunction.PRICES);
        Sources.json(env, "trade.event", "flink-pnl", true, Trade.class)
                .keyBy(t -> t.userId + ":" + t.contestId)
                .connect(ticks)
                .process(new PnlFunction()).name("pnl").uid("pnl")
                .sinkTo(Sources.<RankUpdate>sink("rank.update", u -> Long.toString(u.contestId))).name("rank-update");
        env.execute("tradesim-pnl");
    }
}
