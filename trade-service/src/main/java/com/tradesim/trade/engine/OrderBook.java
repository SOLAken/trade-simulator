package com.tradesim.trade.engine;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;
import com.tradesim.trade.exec.MarketPricer;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 单只股票的订单簿（玩家单 vs 模拟市场）。纯内存、无 IO、无锁——由所属分片的单线程独占访问。
 *
 * <h3>成交规则</h3>
 * <ul>
 *   <li>流动性：每个 tick 的成交量增量即该 tick 可成交的股数，买卖两侧各一份，按「价格优先、时间优先」分配。
 *       大单会被拆成多笔部分成交，跨多个 tick 完成。</li>
 *   <li>限价买：最新价 ≤ 限价 且未涨停封板时，按最新价成交（价格改善归玩家）；限价卖对称。</li>
 *   <li>市价单：最新价 ± 滑点立即成交当前 tick 剩余流动性，余量撤销（IOC）；封板无对手盘直接撤销。</li>
 *   <li>止损单：最新价触及触发价后转为市价单（买入止损 last ≥ stop，卖出止损 last ≤ stop）。</li>
 * </ul>
 */
public final class OrderBook {
    private static final long MIN_LIQUIDITY = 100;

    private final String symbol;
    private final IntSupplier slippageBps;
    /** 限价买：价格降序；限价卖：价格升序。同价位 FIFO。 */
    private final TreeMap<Long, ArrayDeque<BookOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<BookOrder>> asks = new TreeMap<>();
    /** 买入止损：触发价升序（价格涨到即触发）；卖出止损：触发价降序。 */
    private final TreeMap<Long, ArrayDeque<BookOrder>> buyStops = new TreeMap<>();
    private final TreeMap<Long, ArrayDeque<BookOrder>> sellStops = new TreeMap<>(Comparator.reverseOrder());
    private final Map<Long, BookOrder> index = new HashMap<>();

    private MarketTick last;
    private long prevVolume;
    private long bidLiquidity;
    private long askLiquidity;
    private long seq;

    public OrderBook(String symbol, IntSupplier slippageBps) {
        this.symbol = symbol;
        this.slippageBps = slippageBps;
    }

    // ------------------------------------------------------------------ 事件

    public void onTick(MarketTick t, Consumer<EngineAction> out) {
        if (last != null && t.tradingDay() > last.tradingDay()) {
            expireBefore(t.tradingDay(), out);
            prevVolume = 0;
        }
        long delta = t.volume() >= prevVolume ? t.volume() - prevVolume : t.volume();
        prevVolume = t.volume();
        last = t;
        bidLiquidity = askLiquidity = Math.max(delta, MIN_LIQUIDITY);

        triggerStops(out);
        matchResting(bids, Side.BUY, out);
        matchResting(asks, Side.SELL, out);
    }

    public void onNew(BookOrder o, Consumer<EngineAction> out) {
        o.seq = ++seq;
        switch (o.type) {
            case MARKET -> executeMarket(o, out);
            case LIMIT -> {
                add(o.side == Side.BUY ? bids : asks, o.price, o);
                // 新单若已可成交，用当前 tick 剩余流动性立即撮合
                if (last != null) matchResting(o.side == Side.BUY ? bids : asks, o.side, out);
            }
            case STOP -> {
                if (last != null && stopTriggered(o, last.last())) {
                    executeMarket(o, out);
                } else {
                    add(o.side == Side.BUY ? buyStops : sellStops, o.stopPrice, o);
                }
            }
        }
    }

    /** @return 订单在簿中并已移除时返回 true */
    public boolean onCancel(long orderId, OrderStatus status, String reason, Consumer<EngineAction> out) {
        BookOrder o = index.get(orderId);
        if (o == null) return false;
        remove(o);
        out.accept(new EngineAction.Cancel(orderId, status, reason));
        return true;
    }

    /** 跨交易日：上一交易日未成交的挂单全部过期（与 RocketMQ 定时消息互为兜底，账本层幂等）。 */
    public void expireBefore(int day, Consumer<EngineAction> out) {
        List<BookOrder> stale = index.values().stream().filter(o -> o.tradingDay < day).toList();
        for (BookOrder o : stale) {
            remove(o);
            out.accept(new EngineAction.Cancel(o.orderId, OrderStatus.CANCELED, "收盘未成交，自动撤销"));
        }
    }

    // ------------------------------------------------------------------ 撮合

    private void matchResting(TreeMap<Long, ArrayDeque<BookOrder>> book, Side side, Consumer<EngineAction> out) {
        long px = last.last();
        boolean sealed = side == Side.BUY ? px >= last.limitUp() : px <= last.limitDown();
        if (sealed) return;    // 封板：本方向无对手盘
        while (!book.isEmpty() && liquidity(side) > 0) {
            Map.Entry<Long, ArrayDeque<BookOrder>> level = book.firstEntry();
            boolean crosses = side == Side.BUY ? px <= level.getKey() : px >= level.getKey();
            if (!crosses) break;
            ArrayDeque<BookOrder> queue = level.getValue();
            while (!queue.isEmpty() && liquidity(side) > 0) {
                BookOrder o = queue.peekFirst();
                long q = Math.min(o.remaining, liquidity(side));
                consume(side, q);
                o.remaining -= q;
                out.accept(new EngineAction.Fill(o.orderId, px, q, false));
                if (o.remaining == 0) {
                    queue.pollFirst();
                    index.remove(o.orderId);
                }
            }
            if (queue.isEmpty()) book.pollFirstEntry();
        }
    }

    private void triggerStops(Consumer<EngineAction> out) {
        long px = last.last();
        drainTriggered(buyStops, s -> px >= s, out);
        drainTriggered(sellStops, s -> px <= s, out);
    }

    private void drainTriggered(TreeMap<Long, ArrayDeque<BookOrder>> stops,
                                java.util.function.LongPredicate hit, Consumer<EngineAction> out) {
        while (!stops.isEmpty() && hit.test(stops.firstKey())) {
            for (BookOrder o : stops.pollFirstEntry().getValue()) {
                index.remove(o.orderId);
                executeMarket(o, out);
            }
        }
    }

    private void executeMarket(BookOrder o, Consumer<EngineAction> out) {
        if (last == null) {
            out.accept(new EngineAction.Cancel(o.orderId, OrderStatus.CANCELED, "行情中断"));
            return;
        }
        long px = MarketPricer.fillPrice(o.side, last, slippageBps.getAsInt());
        long q = Math.min(o.remaining, liquidity(o.side));
        if (px < 0 || q <= 0) {
            out.accept(new EngineAction.Cancel(o.orderId, OrderStatus.CANCELED, px < 0 ? "封板无对手盘" : "流动性不足"));
            return;
        }
        consume(o.side, q);
        o.remaining -= q;
        out.accept(new EngineAction.Fill(o.orderId, px, q, true));
    }

    // ------------------------------------------------------------------ 簿维护

    private void add(TreeMap<Long, ArrayDeque<BookOrder>> book, long key, BookOrder o) {
        book.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(o);
        index.put(o.orderId, o);
    }

    private void remove(BookOrder o) {
        index.remove(o.orderId);
        TreeMap<Long, ArrayDeque<BookOrder>> book = switch (o.type) {
            case LIMIT -> o.side == Side.BUY ? bids : asks;
            case STOP -> o.side == Side.BUY ? buyStops : sellStops;
            case MARKET -> null;
        };
        if (book == null) return;
        long key = o.type == OrderType.LIMIT ? o.price : o.stopPrice;
        ArrayDeque<BookOrder> q = book.get(key);
        if (q != null && q.remove(o) && q.isEmpty()) book.remove(key);
    }

    private static boolean stopTriggered(BookOrder o, long px) {
        return o.side == Side.BUY ? px >= o.stopPrice : px <= o.stopPrice;
    }

    private long liquidity(Side side) {
        return side == Side.BUY ? bidLiquidity : askLiquidity;
    }

    private void consume(Side side, long q) {
        if (side == Side.BUY) bidLiquidity -= q;
        else askLiquidity -= q;
    }

    public int size() { return index.size(); }
    public boolean contains(long orderId) { return index.containsKey(orderId); }
    public String symbol() { return symbol; }
}
