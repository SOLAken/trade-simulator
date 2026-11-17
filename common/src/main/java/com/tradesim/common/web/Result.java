package com.tradesim.common.web;

public record Result<T>(int code, String msg, T data) {
    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }
    public static <T> Result<T> fail(ErrorCode e) { return new Result<>(e.code(), e.msg(), null); }
    public static <T> Result<T> fail(int code, String msg) { return new Result<>(code, msg, null); }
}
