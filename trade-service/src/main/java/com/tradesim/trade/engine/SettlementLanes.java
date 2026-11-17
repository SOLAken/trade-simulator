package com.tradesim.trade.engine;

import com.tradesim.trade.ledger.OrderLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 结算通道：把撮合动作（成交/撤单）落到 MySQL + RocketMQ + Redis。
 * <p>
 * 按 orderId 哈希到固定通道（单线程），保证同一订单的「成交 → 成交 → 撤单」严格按引擎产出顺序执行——
 * 撤单计算的退回金额依赖之前所有成交已落库。不同订单并行，IO 不会阻塞撮合线程。
 */
public final class SettlementLanes implements ActionSink, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SettlementLanes.class);

    private final ExecutorService[] lanes;
    private final OrderLedger ledger;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public SettlementLanes(int n, OrderLedger ledger) {
        this.ledger = ledger;
        this.lanes = new ExecutorService[n];
        for (int i = 0; i < n; i++) {
            int idx = i;
            AtomicInteger c = new AtomicInteger();
            lanes[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "settle-lane-" + idx + "-" + c.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 提交一批动作；返回全部执行完毕的 future（下单接口用来等待市价单结果）。 */
    @Override
    public CompletableFuture<Void> submit(List<EngineAction> actions) {
        if (actions.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<?>[] fs = new CompletableFuture<?>[actions.size()];
        for (int i = 0; i < fs.length; i++) {
            EngineAction a = actions.get(i);
            pending.incrementAndGet();
            fs[i] = CompletableFuture.runAsync(() -> execute(a), lane(a.orderId()));
        }
        return CompletableFuture.allOf(fs);
    }

    private void execute(EngineAction a) {
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    switch (a) {
                        case EngineAction.Fill f -> ledger.settle(f.orderId(), f.price(), f.qty(), f.closeRest());
                        case EngineAction.Cancel c -> ledger.cancel(c.orderId(), c.status(), c.reason());
                    }
                    return;
                } catch (RuntimeException e) {
                    if (attempt >= 3) throw e;
                    log.warn("settle {} attempt {} failed: {}", a, attempt, e.toString());
                    Thread.sleep(50L * attempt);
                }
            }
        } catch (Exception e) {
            // 引擎已认为成交但账本未落地：记录并告警，由对账任务修复（订单冻结资金在收盘时会被释放）
            failed.incrementAndGet();
            log.error("SETTLEMENT FAILED {}", a, e);
        } finally {
            pending.decrementAndGet();
        }
    }

    private ExecutorService lane(long orderId) {
        return lanes[(int) Math.floorMod(Long.hashCode(orderId), (long) lanes.length)];
    }

    @Override public long pending() { return pending.get(); }
    @Override public long failed() { return failed.get(); }

    @Override
    public void close() throws InterruptedException {
        for (ExecutorService e : lanes) e.shutdown();
        for (ExecutorService e : lanes) e.awaitTermination(10, TimeUnit.SECONDS);
    }
}
