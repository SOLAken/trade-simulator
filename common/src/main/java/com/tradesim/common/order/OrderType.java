package com.tradesim.common.order;

public enum OrderType {
    /** 市价单：按最新价 ± 滑点立即成交，剩余撤销（IOC） */
    MARKET(1),
    /** 限价单：行情穿价时按限价成交，收盘未成交自动撤销 */
    LIMIT(2),
    /** 止损单：行情触及触发价后转为市价单 */
    STOP(3);
    public final int code;
    OrderType(int code) { this.code = code; }
    public static OrderType of(int code) {
        for (OrderType t : values()) if (t.code == code) return t;
        throw new IllegalArgumentException("bad order type " + code);
    }
}
