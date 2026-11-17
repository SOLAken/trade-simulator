package com.tradesim.trade.engine;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 撮合动作的下游。生产实现是 {@link SettlementLanes}；基准测试用空实现以单独度量撮合本身。 */
public interface ActionSink {
    CompletableFuture<Void> submit(List<EngineAction> actions);

    default long pending() { return 0; }

    default long failed() { return 0; }
}
