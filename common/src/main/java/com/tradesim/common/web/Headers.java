package com.tradesim.common.web;

/** Gateway 校验 JWT 后向下游透传的请求头。下游只信任这些头，不再解析令牌。 */
public final class Headers {
    private Headers() {}
    public static final String USER_ID = "X-User-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
}
