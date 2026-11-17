package com.tradesim.flink.risk;

import com.tradesim.flink.model.Events.Alert;
import com.tradesim.flink.model.Events.Order;
import com.tradesim.flink.model.Events.RiskRules;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.util.Collector;

import java.util.Iterator;
import java.util.Map;

/**
 * 按用户识别两类异常下单行为（keyBy userId）：
 * <ol>
 *   <li><b>高频下单</b>：最近 10 秒新下单数 &gt; maxOrdersPer10s。用「每秒一个桶」的 MapState 实现滑动计数，
 *       比 Flink 滑动窗口更省状态（窗口会把同一事件复制进 10/步长 个窗口），且阈值可随广播规则动态变化。</li>
 *   <li><b>挂撤刷单</b>：限价单在 quickCancelMillis 内被撤销视为一次「快撤」，30 秒内快撤数 &gt; 阈值即告警——
 *       典型的虚假挂单诱导（spoofing）行为。</li>
 * </ol>
 * 同类告警 60 秒冷却，避免告警风暴。状态全部带定时清理，不会随用户数无限增长。
 */
public class OrderBehaviorFunction extends KeyedBroadcastProcessFunction<Long, Order, RiskRules, Alert> {
    static final int STATUS_NEW = 0, STATUS_CANCELED = 3, TYPE_LIMIT = 2;
    static final long COOLDOWN_MS = 60_000;

    private transient MapState<Long, Integer> placeBuckets;     // 秒 → 下单数
    private transient MapState<Long, Long> openLimitOrders;      // orderId → 下单时间
    private transient MapState<Long, Integer> quickCancelBuckets; // 秒 → 快撤数
    private transient MapState<String, Long> lastAlert;          // 告警类型 → 上次告警时间

    @Override
    public void open(Configuration parameters) {
        placeBuckets = getRuntimeContext().getMapState(new MapStateDescriptor<>("place", Types.LONG, Types.INT));
        openLimitOrders = getRuntimeContext().getMapState(new MapStateDescriptor<>("open", Types.LONG, Types.LONG));
        quickCancelBuckets = getRuntimeContext().getMapState(new MapStateDescriptor<>("qc", Types.LONG, Types.INT));
        lastAlert = getRuntimeContext().getMapState(new MapStateDescriptor<>("alerted", Types.STRING, Types.LONG));
    }

    @Override
    public void processElement(Order o, ReadOnlyContext ctx, Collector<Alert> out) throws Exception {
        RiskRules rules = RuleState.get(ctx.getBroadcastState(RuleState.DESC));
        long now = ctx.timerService().currentProcessingTime();
        long sec = now / 1000;

        if (o.status == STATUS_NEW) {
            placeBuckets.put(sec, nz(placeBuckets.get(sec)) + 1);
            int recent = sumSince(placeBuckets, sec - 9);
            if (recent > rules.maxOrdersPer10s) {
                alert(out, o, "FREQUENT_ORDERS", "10 秒内下单 " + recent + " 笔（阈值 " + rules.maxOrdersPer10s + "）", now);
            }
            if (o.type == TYPE_LIMIT) {
                openLimitOrders.put(o.orderId, now);
                ctx.timerService().registerProcessingTimeTimer(now + rules.quickCancelMillis + 1);
            }
        } else if (o.status == STATUS_CANCELED) {
            Long placedAt = openLimitOrders.get(o.orderId);
            if (placedAt != null) {
                openLimitOrders.remove(o.orderId);
                if (now - placedAt <= rules.quickCancelMillis) {
                    quickCancelBuckets.put(sec, nz(quickCancelBuckets.get(sec)) + 1);
                    int quick = sumSince(quickCancelBuckets, sec - 29);
                    if (quick > rules.maxQuickCancelsPer30s) {
                        alert(out, o, "SPOOFING", "30 秒内挂单后 " + rules.quickCancelMillis + "ms 内撤单 " + quick
                                + " 次（阈值 " + rules.maxQuickCancelsPer30s + "）", now);
                    }
                }
            }
        } else {
            openLimitOrders.remove(o.orderId);    // 已成交/拒绝：不再可能「快撤」
        }
        ctx.timerService().registerProcessingTimeTimer((sec + 31) * 1000);
    }

    /** 定时清理：过期桶、超过快撤窗口仍未撤的挂单。 */
    @Override
    public void onTimer(long ts, OnTimerContext ctx, Collector<Alert> out) throws Exception {
        RiskRules rules = RuleState.get(ctx.getBroadcastState(RuleState.DESC));
        long sec = ts / 1000;
        evictBefore(placeBuckets, sec - 10);
        evictBefore(quickCancelBuckets, sec - 30);
        Iterator<Map.Entry<Long, Long>> it = openLimitOrders.iterator();
        while (it.hasNext()) if (ts - it.next().getValue() > rules.quickCancelMillis) it.remove();
    }

    @Override
    public void processBroadcastElement(RiskRules rules, Context ctx, Collector<Alert> out) throws Exception {
        ctx.getBroadcastState(RuleState.DESC).put(RuleState.KEY, rules);
    }

    private void alert(Collector<Alert> out, Order o, String type, String detail, long now) throws Exception {
        Long last = lastAlert.get(type);
        if (last != null && now - last < COOLDOWN_MS) return;
        lastAlert.put(type, now);
        out.collect(new Alert(o.userId, o.contestId, type, detail, now));
    }

    private static int sumSince(MapState<Long, Integer> buckets, long fromSec) throws Exception {
        int sum = 0;
        for (Map.Entry<Long, Integer> e : buckets.entries()) if (e.getKey() >= fromSec) sum += e.getValue();
        return sum;
    }

    private static void evictBefore(MapState<Long, Integer> buckets, long sec) throws Exception {
        Iterator<Map.Entry<Long, Integer>> it = buckets.iterator();
        while (it.hasNext()) if (it.next().getKey() < sec) it.remove();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
