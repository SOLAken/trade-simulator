package com.tradesim.common.order;

public enum Side {
    BUY(1), SELL(2);
    public final int code;
    Side(int code) { this.code = code; }
    public static Side of(int code) { return code == 1 ? BUY : SELL; }
}
