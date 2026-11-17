package com.tradesim.trade.bench;

import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.order.Side;
import com.tradesim.trade.engine.ActionSink;
import com.tradesim.trade.engine.BookOrder;
import com.tradesim.trade.engine.EngineAction;
import com.tradesim.trade.engine.MatchingEngine;
import com.tradesim.trade.engine.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 撮合引擎基准。
 * <ul>
 *   <li>{@code book*}：单个订单簿、单线程，度量纯撮合算法（TreeMap + ArrayDeque）的开销</li>
 *   <li>{@code engine}：4 分片 Disruptor，8 个生产线程并发下单 + 行情（BlockingWaitStrategy，空结算），度量端到端吞吐</li>
 * </ul>
 * 运行：./deploy/scripts/bench.sh
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class EngineBench {

    // ------------------------------------------------------------------ 单簿

    @State(Scope.Thread)
    public static class BookState {
        OrderBook book;
        SplittableRandom rnd;
        long id;
        long volume;

        @Setup(Level.Iteration)
        public void setup() {
            book = new OrderBook("SIM001", () -> 5);
            rnd = new SplittableRandom(42);
            id = 0;
            volume = 0;
            tick(1000);
            // 预挂 5000 张单形成有深度的簿
            for (int i = 0; i < 5000; i++) add();
        }

        void tick(long px) {
            volume += 2000 + rnd.nextInt(8000);
            book.onTick(new MarketTick("SIM001", px, 1000, 1100, 900, 1000, 1100, 900, volume, 1, System.currentTimeMillis()), a -> { });
        }

        void add() {
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long px = side == Side.BUY ? 950 + rnd.nextInt(50) : 1001 + rnd.nextInt(50);
            book.onNew(new BookOrder(++id, 1, "SIM001", side, OrderType.LIMIT, px, 0, 100L * (1 + rnd.nextInt(10)), 1), a -> { });
        }
    }

    /** 下单 70% / 撤单 20% / 行情 10% 的混合负载 */
    @Benchmark
    public void bookMixed(BookState s, Blackhole bh) {
        int r = s.rnd.nextInt(10);
        if (r < 7) {
            s.add();
        } else if (r < 9) {
            s.book.onCancel(s.id - s.rnd.nextInt(1000), OrderStatus.CANCELED, "x", bh::consume);
        } else {
            s.tick(960 + s.rnd.nextInt(80));
        }
    }

    // ------------------------------------------------------------------ 引擎

    @State(Scope.Benchmark)
    public static class EngineState {
        MatchingEngine engine;
        final AtomicLong ids = new AtomicLong();
        final String[] symbols = {"SIM001", "SIM002", "SIM003", "SIM004", "SIM005", "SIM006", "SIM007", "SIM008"};

        @Setup(Level.Trial)
        public void setup() {
            ActionSink noop = actions -> CompletableFuture.completedFuture(null);
            engine = new MatchingEngine(4, 1 << 16, () -> 5, noop);
            for (String s : symbols) {
                engine.onTick(new MarketTick(s, 1000, 1000, 1100, 900, 1000, 1100, 900, 1_000_000, 1, System.currentTimeMillis()));
            }
        }

        @TearDown(Level.Trial)
        public void close() {
            engine.close();
        }
    }

    @State(Scope.Thread)
    public static class Producer {
        final SplittableRandom rnd = new SplittableRandom(Thread.currentThread().getId());
    }

    @State(Scope.Thread)
    public static class TickClock {
        long volume = 1_000_000;
    }

    /**
     * 8 线程并发：80% 下单（限价在 ±3% 内，多数会被后续 tick 成交）、20% 行情 tick（补充流动性）。
     * 订单簿规模保持有界——早期版本只下单不发 tick，订单永不成交、簿无限膨胀，测到的是堆增长而不是撮合。
     */
    @Benchmark
    @Threads(8)
    public void engine(EngineState s, Producer p, TickClock c) {
        String sym = s.symbols[p.rnd.nextInt(s.symbols.length)];
        if (p.rnd.nextInt(10) < 2) {
            c.volume += 20_000;
            long px = 970 + p.rnd.nextInt(61);
            s.engine.onTick(new MarketTick(sym, px, 1000, 1100, 900, 1000, 1100, 900, c.volume, 1, System.currentTimeMillis()));
            return;
        }
        Side side = p.rnd.nextBoolean() ? Side.BUY : Side.SELL;
        long px = 970 + p.rnd.nextInt(61);
        s.engine.submit(new BookOrder(s.ids.incrementAndGet(), 1, sym, side, OrderType.LIMIT, px, 0, 100, 1));
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(EngineBench.class.getSimpleName())
                .addProfiler("gc")
                .build()).run();
    }
}
