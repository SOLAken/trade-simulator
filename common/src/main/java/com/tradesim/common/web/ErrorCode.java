package com.tradesim.common.web;

public enum ErrorCode {
    UNAUTHORIZED(401, "未登录或令牌失效"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    BAD_REQUEST(400, "参数错误"),
    DUPLICATE_REQUEST(1001, "重复请求"),
    INSUFFICIENT_FUNDS(2001, "可用资金不足"),
    INSUFFICIENT_POSITION(2002, "可卖持仓不足"),
    PRICE_OUT_OF_LIMIT(2003, "价格超出涨跌停范围"),
    MARKET_CLOSED(2004, "休市中"),
    NO_ACCOUNT(2005, "尚未加入该比赛"),
    NO_QUOTE(2006, "暂无行情"),
    ORDER_NOT_FOUND(2007, "订单不存在"),
    RISK_BLOCKED(3001, "账户已被风控限制");

    private final int code;
    private final String msg;
    ErrorCode(int code, String msg) { this.code = code; this.msg = msg; }
    public int code() { return code; }
    public String msg() { return msg; }
}
