package com.tradesim.common.id;

/**
 * 基因法 Snowflake：把 user_id 的低 GENE_BITS 位嵌入 ID 末尾，
 * 使订单/成交 ID 与 user_id 落在同一分片，按 order_id 查询也能直接路由。
 *
 * 布局（63 位有效）：41 位毫秒时间戳 | 5 位机器 | 11 位序列 | 6 位基因
 */
public final class GeneSnowflake {
    public static final int GENE_BITS = 6;              // 支持最多 64 个分片（2库×4表=8 绰绰有余）
    private static final int SEQ_BITS = 11;
    private static final int WORKER_BITS = 5;
    private static final long EPOCH = 1767225600000L;  // 2026-01-01T00:00:00Z

    private static final long GENE_MASK = (1L << GENE_BITS) - 1;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;

    private final long workerId;
    private long lastTs = -1L;
    private long seq = 0L;

    public GeneSnowflake(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId out of range: " + workerId);
        }
        this.workerId = workerId;
    }

    public synchronized long nextId(long userId) {
        long ts = System.currentTimeMillis();
        if (ts < lastTs) {
            // 时钟回拨：小幅回拨等待追上，大幅直接拒绝
            if (lastTs - ts > 5) throw new IllegalStateException("clock moved backwards " + (lastTs - ts) + "ms");
            ts = waitUntil(lastTs);
        }
        if (ts == lastTs) {
            seq = (seq + 1) & SEQ_MASK;
            if (seq == 0) ts = waitUntil(lastTs + 1);
        } else {
            seq = 0;
        }
        lastTs = ts;
        return ((ts - EPOCH) << (WORKER_BITS + SEQ_BITS + GENE_BITS))
                | (workerId << (SEQ_BITS + GENE_BITS))
                | (seq << GENE_BITS)
                | (userId & GENE_MASK);
    }

    /** 从 ID 中取出基因，等价于 userId & GENE_MASK，分片算法对两者结果一致。 */
    public static long gene(long id) {
        return id & GENE_MASK;
    }

    private static long waitUntil(long target) {
        long ts;
        while ((ts = System.currentTimeMillis()) < target) Thread.onSpinWait();
        return ts;
    }
}
