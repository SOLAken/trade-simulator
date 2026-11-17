package com.tradesim.trade.engine;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {
    static final int DAY = 10;
    OrderBook book;
    List<EngineAction> out;
    long volume;
    long nextId;

    @BeforeEach
    void setUp() {
        book = new OrderBook("SIM001", () -> 0);      // 无滑点，便于断言
        out = new ArrayList<>();
        volume = 0;
        nextId = 1;
    }

    /** 昨收 1000 → 涨停 1100 / 跌停 900；liquidity 为本 tick 成交量增量 */
    void tick(long last, long liquidity) {
        volume += liquidity;
        book.onTick(new MarketTick("SIM001", last, 1000, 1100, 900, 1000, 1100, 900, volume, DAY, System.currentTimeMillis()), out::add);
    }

    BookOrder order(Side side, OrderType type, long price, long stop, long qty) {
        BookOrder o = new BookOrder(nextId++, 1, "SIM001", side, type, price, stop, qty, DAY);
        book.onNew(o, out::add);
        return o;
    }

    List<EngineAction.Fill> fills() {
        return out.stream().filter(a -> a instanceof EngineAction.Fill).map(a -> (EngineAction.Fill) a).toList();
    }

    @Test
    void limitBuyRestsUntilPriceCrossesThenFillsAtMarketPrice() {
        tick(1000, 10_000);
        order(Side.BUY, OrderType.LIMIT, 990, 0, 500);
        assertThat(out).isEmpty();
        tick(995, 10_000);
        assertThat(out).isEmpty();
        tick(985, 10_000);                                    // 穿价：按 985 成交（价格改善）
        assertThat(fills()).containsExactly(new EngineAction.Fill(1, 985, 500, false));
        assertThat(book.size()).isZero();
    }

    @Test
    void marketableLimitFillsImmediately() {
        tick(1000, 10_000);
        order(Side.SELL, OrderType.LIMIT, 990, 0, 300);
        assertThat(fills()).containsExactly(new EngineAction.Fill(1, 1000, 300, false));
    }

    @Test
    void priceThenTimePriorityWithLimitedLiquidity() {
        tick(1000, 100);
        order(Side.BUY, OrderType.LIMIT, 980, 0, 400);       // id1 价低
        order(Side.BUY, OrderType.LIMIT, 990, 0, 400);       // id2 价高 → 优先
        order(Side.BUY, OrderType.LIMIT, 990, 0, 400);       // id3 同价晚到
        tick(975, 600);                                       // 只有 600 股流动性
        assertThat(fills()).containsExactly(
                new EngineAction.Fill(2, 975, 400, false),
                new EngineAction.Fill(3, 975, 200, false));
        out.clear();
        tick(975, 1000);                                      // 下个 tick 继续：id3 剩 200，再到 id1
        assertThat(fills()).containsExactly(
                new EngineAction.Fill(3, 975, 200, false),
                new EngineAction.Fill(1, 975, 400, false));
        assertThat(book.size()).isZero();
    }

    @Test
    void limitUpSealBlocksBuysButNotSells() {
        tick(1100, 10_000);                                   // 涨停封板
        order(Side.BUY, OrderType.LIMIT, 1100, 0, 100);      // 封板时买单只能排队
        order(Side.SELL, OrderType.LIMIT, 1050, 0, 100);     // 卖单可以按涨停价卖出
        assertThat(fills()).containsExactly(new EngineAction.Fill(2, 1100, 100, false));
        assertThat(book.contains(1)).isTrue();
        out.clear();
        tick(1100, 10_000);                                   // 仍封板：买单继续排队
        assertThat(fills()).isEmpty();
        tick(1090, 10_000);                                   // 开板：成交
        assertThat(fills()).containsExactly(new EngineAction.Fill(1, 1090, 100, false));
    }

    @Test
    void marketOrderIsIocAndCancelsWhenSealed() {
        tick(1000, 300);
        order(Side.BUY, OrderType.MARKET, 0, 0, 500);         // 只有 300 流动性：成交 300，余量撤销
        assertThat(out).containsExactly(new EngineAction.Fill(1, 1000, 300, true));
        out.clear();
        tick(1100, 10_000);
        order(Side.BUY, OrderType.MARKET, 0, 0, 100);
        assertThat(out).containsExactly(new EngineAction.Cancel(2, OrderStatus.CANCELED, "封板无对手盘"));
    }

    @Test
    void stopLossTriggersAsMarketOrder() {
        tick(1000, 10_000);
        order(Side.SELL, OrderType.STOP, 0, 950, 200);
        tick(960, 10_000);
        assertThat(out).isEmpty();
        tick(948, 10_000);
        assertThat(out).containsExactly(new EngineAction.Fill(1, 948, 200, true));
        assertThat(book.size()).isZero();
    }

    @Test
    void cancelRemovesOnlyRestingOrders() {
        tick(1000, 10_000);
        order(Side.BUY, OrderType.LIMIT, 900, 0, 100);
        assertThat(book.onCancel(1, OrderStatus.CANCELED, "用户撤单", out::add)).isTrue();
        assertThat(book.onCancel(1, OrderStatus.CANCELED, "用户撤单", out::add)).isFalse();
        tick(900, 10_000);
        assertThat(fills()).isEmpty();
    }

    @Test
    void newTradingDayExpiresYesterdaysOrders() {
        tick(1000, 10_000);
        order(Side.BUY, OrderType.LIMIT, 900, 0, 100);
        book.onTick(new MarketTick("SIM001", 1000, 1000, 1000, 1000, 1000, 1100, 900, 100, DAY + 1, System.currentTimeMillis()), out::add);
        assertThat(out).containsExactly(new EngineAction.Cancel(1, OrderStatus.CANCELED, "收盘未成交，自动撤销"));
    }

    @Test
    void filledQuantityNeverExceedsOrderQuantity() {
        // 性质测试：随机行情下，任一订单累计成交量 ≤ 下单量，簿清空后两者相等或订单已撤销
        var rnd = new java.util.Random(7);
        tick(1000, 1000);
        long[] qty = new long[201];
        for (int i = 1; i <= 200; i++) {
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long q = (1 + rnd.nextInt(20)) * 100L;
            qty[i] = q;
            order(side, OrderType.LIMIT, 950 + rnd.nextInt(100), 0, q);
        }
        for (int t = 0; t < 500; t++) tick(910 + rnd.nextInt(180), rnd.nextInt(3000));
        long[] filled = new long[201];
        for (EngineAction.Fill f : fills()) filled[(int) f.orderId()] += f.qty();
        for (int i = 1; i <= 200; i++) {
            assertThat(filled[i]).isLessThanOrEqualTo(qty[i]);
            if (!book.contains(i)) assertThat(filled[i]).isEqualTo(qty[i]);
        }
    }
}
