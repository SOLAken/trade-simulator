package com.tradesim.flink;

import com.tradesim.flink.model.Events.*;
import com.tradesim.flink.pnl.PnlFunction;
import com.tradesim.flink.risk.AbnormalReturnFunction;
import com.tradesim.flink.risk.OrderBehaviorFunction;
import com.tradesim.flink.risk.RuleState;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.util.KeyedBroadcastOperatorTestHarness;
import org.apache.flink.streaming.util.ProcessFunctionTestHarnesses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlinkFunctionsTest {

    // ------------------------------------------------------------------ 风控：下单行为

    static KeyedBroadcastOperatorTestHarness<Long, Order, RiskRules, Alert> behavior() throws Exception {
        var h = ProcessFunctionTestHarnesses.forKeyedBroadcastProcessFunction(
                new OrderBehaviorFunction(), o -> o.userId, Types.LONG, RuleState.DESC);
        h.open();
        return h;
    }

    static Order order(long id, int status, int type) {
        Order o = new Order();
        o.orderId = id;
        o.userId = 7;
        o.contestId = 1;
        o.status = status;
        o.type = type;
        return o;
    }

    static List<Alert> alerts(KeyedBroadcastOperatorTestHarness<?, ?, ?, Alert> h) {
        return h.extractOutputValues();
    }

    @Test
    void frequentOrdersAlertOnceWithinCooldown() throws Exception {
        var h = behavior();
        h.setProcessingTime(1_000);
        for (int i = 0; i < 25; i++) h.processElement(order(i, 0, 1), 1_000);
        assertThat(alerts(h)).hasSize(1);
        assertThat(alerts(h).get(0).type).isEqualTo("FREQUENT_ORDERS");
    }

    @Test
    void slidingWindowForgetsOldOrders() throws Exception {
        var h = behavior();
        h.setProcessingTime(1_000);
        for (int i = 0; i < 15; i++) h.processElement(order(i, 0, 1), 1_000);
        h.setProcessingTime(12_000);                          // 11 秒后：前 15 单已滑出窗口
        for (int i = 15; i < 30; i++) h.processElement(order(i, 0, 1), 12_000);
        assertThat(alerts(h)).isEmpty();
    }

    @Test
    void ruleBroadcastChangesThresholdAtRuntime() throws Exception {
        var h = behavior();
        RiskRules strict = new RiskRules();
        strict.maxOrdersPer10s = 3;
        h.processBroadcastElement(strict, 0);
        h.setProcessingTime(1_000);
        for (int i = 0; i < 4; i++) h.processElement(order(i, 0, 1), 1_000);
        assertThat(alerts(h)).extracting(a -> a.type).containsExactly("FREQUENT_ORDERS");
    }

    @Test
    void quickPlaceThenCancelIsSpoofing() throws Exception {
        var h = behavior();
        long t = 1_000;
        for (int i = 0; i < 9; i++) {                         // 9 次「挂限价单 → 500ms 后撤」
            h.setProcessingTime(t);
            h.processElement(order(i, 0, 2), t);
            h.setProcessingTime(t + 500);
            h.processElement(order(i, 3, 2), t + 500);
            t += 2_000;
        }
        assertThat(alerts(h)).extracting(a -> a.type).containsExactly("SPOOFING");
    }

    @Test
    void slowCancelOrMarketIocIsNotSpoofing() throws Exception {
        var h = behavior();
        long t = 1_000;
        for (int i = 0; i < 12; i++) {
            h.setProcessingTime(t);
            h.processElement(order(i, 0, 2), t);              // 限价单挂 5 秒后才撤
            h.setProcessingTime(t + 5_000);
            h.processElement(order(i, 3, 2), t + 5_000);
            h.processElement(order(100 + i, 0, 1), t + 5_000); // 市价单 IOC 撤余量
            h.processElement(order(100 + i, 3, 1), t + 5_000);
            t += 2_000;
        }
        assertThat(alerts(h)).isEmpty();
    }

    // ------------------------------------------------------------------ 风控：异常收益

    @Test
    void outlierPnlAgainstPeersIsFlagged() throws Exception {
        var h = ProcessFunctionTestHarnesses.forKeyedBroadcastProcessFunction(
                new AbnormalReturnFunction(), (RankUpdate u) -> u.contestId, Types.LONG, RuleState.DESC);
        h.open();
        long[] peers = {-2_000_000, 1_500_000, 500_000, -800_000, 2_200_000, -1_000_000};   // ±2 万元量级
        for (int i = 0; i < peers.length; i++) h.processElement(new RankUpdate(i, 1, peers[i], 0), 0);
        assertThat(alerts(h)).isEmpty();
        h.processElement(new RankUpdate(99, 1, 30_000_000, 0), 0);                  // +30 万元
        assertThat(alerts(h)).extracting(a -> a.userId).containsExactly(99L);
        h.processElement(new RankUpdate(99, 1, 31_000_000, 0), 0);                  // 冷却期内不重复告警
        assertThat(alerts(h)).hasSize(1);
    }

    @Test
    void earlyContestWithIdlePeersDoesNotFalseAlarm() throws Exception {
        var h = ProcessFunctionTestHarnesses.forKeyedBroadcastProcessFunction(
                new AbnormalReturnFunction(), (RankUpdate u) -> u.contestId, Types.LONG, RuleState.DESC);
        h.open();
        for (int i = 0; i < 10; i++) h.processElement(new RankUpdate(i, 1, 0, 0), 0);
        h.processElement(new RankUpdate(99, 1, 2_000_000, 0), 0);                   // 赚 2 万：正常
        assertThat(alerts(h)).isEmpty();
    }

    // ------------------------------------------------------------------ 收益

    @Test
    void pnlMatchesAccountFormula() throws Exception {
        var h = ProcessFunctionTestHarnesses.forKeyedBroadcastProcessFunction(
                new PnlFunction(), (Trade t) -> t.userId + ":" + t.contestId, Types.STRING, PnlFunction.PRICES);
        h.open();
        h.processBroadcastElement(tick("SIM001", 1000), 0);
        h.processElement(trade(1, 100, 1000, 500), 0);       // 买 100 @10.00，佣金 5 元
        assertThat(lastPnl(h)).isEqualTo(-100_500 + 100_000); // = -500（手续费）
        h.processBroadcastElement(tick("SIM001", 1100), 0);
        h.setProcessingTime(1_000);                           // 定时器按新价重算
        assertThat(lastPnl(h)).isEqualTo(-100_500 + 110_000);
        h.processElement(trade(2, 100, 1100, 610), 1_000);    // 全部卖出 @11.00，佣金+印花税 6.1 元
        assertThat(lastPnl(h)).isEqualTo(-100_500 + 110_000 - 610);
    }

    @Test
    void pnlWaitsForPricesBeforeEmitting() throws Exception {
        var h = ProcessFunctionTestHarnesses.forKeyedBroadcastProcessFunction(
                new PnlFunction(), (Trade t) -> t.userId + ":" + t.contestId, Types.STRING, PnlFunction.PRICES);
        h.open();
        h.processElement(trade(1, 100, 1000, 500), 0);       // 作业刚启动、还没有行情
        assertThat(h.extractOutputValues()).isEmpty();
        h.processBroadcastElement(tick("SIM001", 1000), 0);
        h.setProcessingTime(1_000);
        assertThat(lastPnl(h)).isEqualTo(-500);
    }

    static Tick tick(String s, long px) {
        Tick t = new Tick();
        t.symbol = s;
        t.last = px;
        return t;
    }

    static Trade trade(int side, long qty, long price, long fee) {
        Trade t = new Trade();
        t.userId = 7;
        t.contestId = 1;
        t.symbol = "SIM001";
        t.side = side;
        t.qty = qty;
        t.price = price;
        t.amount = qty * price;
        t.fee = fee;
        return t;
    }

    static long lastPnl(KeyedBroadcastOperatorTestHarness<String, Trade, Tick, RankUpdate> h) {
        List<RankUpdate> out = h.extractOutputValues();
        return out.get(out.size() - 1).pnl;
    }
}
