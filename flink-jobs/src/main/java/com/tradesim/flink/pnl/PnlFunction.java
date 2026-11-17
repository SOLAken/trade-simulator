package com.tradesim.flink.pnl;

import com.tradesim.flink.model.Events.RankUpdate;
import com.tradesim.flink.model.Events.Tick;
import com.tradesim.flink.model.Events.Trade;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.util.Collector;

import java.util.Map;

/**
 * 实时盈亏（keyBy "userId:contestId"）。
 * <pre>
 *   pnl = 现金流 + Σ 持仓 × 最新价
 *   买：现金流 -= 成交额 + 佣金；卖：现金流 += 成交额 - 佣金 - 印花税
 * </pre>
 * 与 Redis 账户口径一致：总资产 = 初始资金 + pnl（挂单冻结的钱没花出去，仍在总资产内）。
 * <p>
 * 行情走广播状态，成交走键控状态。不在每个 tick 上遍历所有用户（tick 每秒 16 条 × N 用户），
 * 而是每个有持仓的用户 1 秒一次处理时间定时器，按广播状态里的最新价重算，值变化才输出。
 */
public class PnlFunction extends KeyedBroadcastProcessFunction<String, Trade, Tick, RankUpdate> {
    public static final MapStateDescriptor<String, Long> PRICES =
            new MapStateDescriptor<>("prices", Types.STRING, Types.LONG);
    static final long INTERVAL_MS = 1000;

    private transient ValueState<Long> cashFlow;
    private transient MapState<String, Long> holdings;
    private transient ValueState<Long> lastEmitted;
    private transient ValueState<Long> timerAt;
    private transient ValueState<long[]> ids;      // [userId, contestId]

    @Override
    public void open(Configuration parameters) {
        cashFlow = getRuntimeContext().getState(new ValueStateDescriptor<>("cash", Types.LONG));
        holdings = getRuntimeContext().getMapState(new MapStateDescriptor<>("holdings", Types.STRING, Types.LONG));
        lastEmitted = getRuntimeContext().getState(new ValueStateDescriptor<>("emitted", Types.LONG));
        timerAt = getRuntimeContext().getState(new ValueStateDescriptor<>("timer", Types.LONG));
        ids = getRuntimeContext().getState(new ValueStateDescriptor<>("ids", PrimitiveArrayTypeInfo.LONG_PRIMITIVE_ARRAY_TYPE_INFO));
    }

    @Override
    public void processElement(Trade t, ReadOnlyContext ctx, Collector<RankUpdate> out) throws Exception {
        ids.update(new long[]{t.userId, t.contestId});
        long cash = cashFlow.value() == null ? 0 : cashFlow.value();
        long qty = holdings.contains(t.symbol) ? holdings.get(t.symbol) : 0;
        if (t.side == 1) {
            cash -= t.amount + t.fee;
            qty += t.qty;
        } else {
            cash += t.amount - t.fee;
            qty -= t.qty;
        }
        cashFlow.update(cash);
        if (qty == 0) holdings.remove(t.symbol);
        else holdings.put(t.symbol, qty);

        emit(ctx.getBroadcastState(PRICES), ctx.timerService().currentProcessingTime(), out);
        if (timerAt.value() == null) {
            long next = ctx.timerService().currentProcessingTime() + INTERVAL_MS;
            ctx.timerService().registerProcessingTimeTimer(next);
            timerAt.update(next);
        }
    }

    @Override
    public void onTimer(long ts, OnTimerContext ctx, Collector<RankUpdate> out) throws Exception {
        emit(ctx.getBroadcastState(PRICES), ts, out);
        if (holdings.isEmpty()) {
            timerAt.clear();          // 空仓：盈亏不再随行情变化，停止定时器
        } else {
            ctx.timerService().registerProcessingTimeTimer(ts + INTERVAL_MS);
            timerAt.update(ts + INTERVAL_MS);
        }
    }

    @Override
    public void processBroadcastElement(Tick tick, Context ctx, Collector<RankUpdate> out) throws Exception {
        ctx.getBroadcastState(PRICES).put(tick.symbol, tick.last);
    }

    private void emit(ReadOnlyBroadcastState<String, Long> prices, long now, Collector<RankUpdate> out) throws Exception {
        Long pnl = pnl(cashFlow.value(), holdings.entries(), prices);
        if (pnl == null || pnl.equals(lastEmitted.value())) return;
        lastEmitted.update(pnl);
        long[] id = ids.value();
        out.collect(new RankUpdate(id[0], id[1], pnl, now));
    }

    /** @return 持仓中有股票暂无价格（作业刚启动、行情还没到）时返回 null，不输出错误的低值 */
    static Long pnl(Long cash, Iterable<Map.Entry<String, Long>> holdings,
                    ReadOnlyBroadcastState<String, Long> prices) throws Exception {
        long total = cash == null ? 0 : cash;
        for (Map.Entry<String, Long> h : holdings) {
            Long px = prices.get(h.getKey());
            if (px == null) return null;
            total += h.getValue() * px;
        }
        return total;
    }
}
