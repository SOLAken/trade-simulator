package com.tradesim.trade.mq;

/**
 * 事务消息的本地事务回调。RocketMQ 在 sendMessageInTransaction 的调用线程里同步执行它，
 * 因此可以把异常带回调用方（如「资金不足」要原样返回给用户）。
 */
public final class TxAction {
    private final Runnable work;
    private RuntimeException error;

    public TxAction(Runnable work) {
        this.work = work;
    }

    boolean run() {
        try {
            work.run();
            return true;
        } catch (RuntimeException e) {
            error = e;
            return false;
        }
    }

    public RuntimeException error() {
        return error;
    }
}
