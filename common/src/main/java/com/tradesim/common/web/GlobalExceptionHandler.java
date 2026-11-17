package com.tradesim.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> biz(BizException e) {
        HttpStatus status = switch (e.error()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Result.fail(e.error()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("参数错误");
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST.code(), msg));
    }

    /** 缺 X-User-Id 说明请求绕过了网关鉴权。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> illegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST.code(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> other(Exception e) {
        log.error("unhandled", e);
        return ResponseEntity.internalServerError().body(Result.fail(500, "服务器内部错误"));
    }
}
