package com.tradesim.common.web;

public class BizException extends RuntimeException {
    private final ErrorCode error;
    public BizException(ErrorCode error) { super(error.msg()); this.error = error; }
    public ErrorCode error() { return error; }
}
