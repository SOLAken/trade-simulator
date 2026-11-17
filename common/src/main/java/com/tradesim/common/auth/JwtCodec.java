package com.tradesim.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/** HS256 JWT。user-service 签发，gateway 校验；下游服务只读 X-User-Id 头。 */
public final class JwtCodec {
    private final SecretKey key;
    private final Duration ttl;

    public JwtCodec(String secret, Duration ttl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
    }

    public String sign(long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim("name", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** @return userId；令牌非法或过期抛 io.jsonwebtoken.JwtException */
    public long verify(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return Long.parseLong(c.getSubject());
    }
}
