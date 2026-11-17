package com.tradesim.trade.engine;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.tradesim.common.market.MarketTick;
import com.tradesim.common.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 撮合引擎：symbol 哈希到 N 个分片，每个分片 = 一个 Disruptor RingBuffer + 一个消费线程 + 该分片所有订单簿。
 *
 * <pre>
 *  下单/撤单(HTTP 线程) ──┐                        ┌─ OrderBook(SIM001)
 *  行情 tick(Kafka 线程) ─┼─► RingBuffer[shard] ─► 单线程 ┼─ OrderBook(SIM005)   ─► 动作 ─► SettlementLanes(IO)
 *                         │   (多生产者，无锁 CAS)          └─ ...
 * </pre>
 * 单写者原则：订单簿只被一个线程修改，免锁且缓存友好；分片间完全独立，可按 CPU 核数水平扩展。
 * 同一 symbol 的所有事件（下单、撤单、tick）进入同一 RingBuffer，天然全序，结果可重放。
 */
public final class MatchingEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);
    private static final long STALE_TICK_MS = 3_000;

    private final Shard[] shards;
    private final ActionSink lanes;
    private final LongAdder events = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();

    public MatchingEngine(int shardCount, int ringSize, IntSupplier slippageBps, ActionSink lanes) {
        this.lanes = lanes;
        this.shards = new Shard[shardCount];
        for (int i = 0; i < shardCount; i++) shards[i] = new Shard(i, ringSize, slippageBps);
    }

    // ------------------------------------------------------------------ 发布（任意线程）

    /** @return 下单同步产生的动作（如市价成交）全部结算后完成 */
    public CompletableFuture<Boolean> submit(BookOrder o) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        shard(o.symbol).publish(e -> {
            e.type = EngineEvent.Type.NEW;
            e.symbol = o.symbol;
            e.order = o;
            e.done = f;
        });
        return f;
    }

    /** @return true 订单在簿中并被撤销 */
    public CompletableFuture<Boolean> cancel(String symbol, long orderId, OrderStatus status, String reason) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        shard(symbol).publish(e -> {
            e.type = EngineEvent.Type.CANCEL;
            e.symbol = symbol;
            e.cancelOrderId = orderId;
            e.cancelStatus = status;
            e.cancelReason = reason;
            e.done = f;
        });
        return f;
    }

    public void onTick(MarketTick t) {
        if (System.currentTimeMillis() - t.ts() > STALE_TICK_MS) return;   // 重启回放的旧行情不能触发成交
        shard(t.symbol()).publish(e -> {
            e.type = EngineEvent.Type.TICK;
            e.symbol = t.symbol();
            e.tick = t;
        });
    }

    private Shard shard(String symbol) {
        return shards[Math.floorMod(symbol.hashCode(), shards.length)];
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        long n = events.sum();
        m.put("events", n);
        m.put("avgLatencyMicros", n == 0 ? 0 : latencyNanos.sum() / n / 1000);
        m.put("settlePending", lanes.pending());
        m.put("settleFailed", lanes.failed());
        List<Map<String, Object>> s = new ArrayList<>();
        for (Shard sh : shards) {
            s.add(Map.of("shard", sh.id, "books", sh.books.size(),
                    "resting", sh.books.values().stream().mapToInt(OrderBook::size).sum(),
                    "ringRemaining", sh.ring.remainingCapacity()));
        }
        m.put("shards", s);
        return m;
    }

    @Override
    public void close() {
        for (Shard s : shards) s.disruptor.shutdown();
    }

    // ------------------------------------------------------------------ 分片

    private final class Shard implements EventHandler<EngineEvent> {
        final int id;
        final Map<String, OrderBook> books = new HashMap<>();   // 仅消费线程访问
        final Disruptor<EngineEvent> disruptor;
        final RingBuffer<EngineEvent> ring;
        final IntSupplier slippageBps;
        final List<EngineAction> buf = new ArrayList<>();
        final Consumer<EngineAction> sink = buf::add;

        Shard(int id, int ringSize, IntSupplier slippageBps) {
            this.id = id;
            this.slippageBps = slippageBps;
            AtomicInteger n = new AtomicInteger();
            // BlockingWaitStrategy：空闲不烧 CPU，适合开发机/容器；生产独占核可换 BusySpin/Yielding 换取更低延迟
            this.disruptor = new Disruptor<>(EngineEvent::new, ringSize, r -> {
                Thread t = new Thread(r, "match-shard-" + id + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, ProducerType.MULTI, new BlockingWaitStrategy());
            disruptor.handleEventsWith(this);
            disruptor.setDefaultExceptionHandler(new LoggingHandler());
            this.ring = disruptor.start();
        }

        void publish(Consumer<EngineEvent> fill) {
            long seq = ring.next();
            try {
                EngineEvent e = ring.get(seq);
                fill.accept(e);
                e.publishNanos = System.nanoTime();
            } finally {
                ring.publish(seq);
            }
        }

        @Override
        public void onEvent(EngineEvent e, long sequence, boolean endOfBatch) {
            try {
                OrderBook book = books.computeIfAbsent(e.symbol, s -> new OrderBook(s, slippageBps));
                buf.clear();
                switch (e.type) {
                    case TICK -> {
                        book.onTick(e.tick, sink);
                        if (!buf.isEmpty()) lanes.submit(List.copyOf(buf));
                    }
                    case NEW -> {
                        book.onNew(e.order, sink);
                        CompletableFuture<Boolean> done = e.done;
                        lanes.submit(List.copyOf(buf)).whenComplete((v, ex) -> done.complete(true));
                    }
                    case CANCEL -> {
                        boolean removed = book.onCancel(e.cancelOrderId, e.cancelStatus, e.cancelReason, sink);
                        CompletableFuture<Boolean> done = e.done;
                        lanes.submit(List.copyOf(buf)).whenComplete((v, ex) -> done.complete(removed));
                    }
                }
                events.increment();
                latencyNanos.add(System.nanoTime() - e.publishNanos);
            } finally {
                e.clear();
            }
        }
    }

    private static final class LoggingHandler implements com.lmax.disruptor.ExceptionHandler<EngineEvent> {
        @Override public void handleEventException(Throwable ex, long seq, EngineEvent e) { log.error("engine event {} failed", seq, ex); }
        @Override public void handleOnStartException(Throwable ex) { log.error("engine start", ex); }
        @Override public void handleOnShutdownException(Throwable ex) { log.error("engine shutdown", ex); }
    }
}
