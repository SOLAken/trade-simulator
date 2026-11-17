package com.tradesim.common.order;

public enum OrderStatus {
    NEW(0), PARTIAL(1), FILLED(2), CANCELED(3), REJECTED(4);
    public final int code;
    OrderStatus(int code) { this.code = code; }
    public boolean isFinal() { return this == FILLED || this == CANCELED || this == REJECTED; }
    public static OrderStatus of(int code) { return values()[code]; }
}
