package com.tradesim.gateway;

import com.tradesim.common.auth.JwtCodec;
import com.tradesim.common.web.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 统一鉴权：校验 JWT → 写入 X-User-Id。
 * 先无条件删除客户端传来的 X-User-Id，防止伪造身份直接打到下游。
 * WebSocket 握手无法自定义 Header，允许用 ?token= 传令牌。
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    /** 公开接口：登录注册、只读行情。 */
    private static final List<String> PUBLIC = List.of(
            "/api/user/login", "/api/user/register", "/api/market/**", "/ws/market/**", "/actuator/**");

    private final JwtCodec jwt;

    public AuthFilter(@Value("${tradesim.jwt-secret}") String secret) {
        this.jwt = new JwtCodec(secret, Duration.ofDays(7));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest().mutate()
                .headers(h -> h.remove(Headers.USER_ID)).build();
        String path = req.getURI().getPath();

        String token = extractToken(req);
        Long userId = null;
        if (token != null) {
            try {
                userId = jwt.verify(token);
            } catch (Exception e) {
                // 令牌无效：公开接口照常放行（匿名），受保护接口拒绝
            }
        }
        boolean isPublic = req.getMethod() == HttpMethod.OPTIONS || PUBLIC.stream().anyMatch(p -> MATCHER.match(p, path));
        if (userId == null && !isPublic) {
            return unauthorized(exchange);
        }
        if (userId != null) {
            final long uid = userId;
            req = req.mutate().header(Headers.USER_ID, Long.toString(uid)).build();
        }
        return chain.filter(exchange.mutate().request(req).build());
    }

    private static String extractToken(ServerHttpRequest req) {
        String auth = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return req.getQueryParams().getFirst("token");
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        var resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":401,\"msg\":\"未登录或令牌失效\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body)));
    }

    /** 在 Sentinel(-2^31) 之后、路由转发之前执行：先限流挡掉洪峰，再做验签这种 CPU 活。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
