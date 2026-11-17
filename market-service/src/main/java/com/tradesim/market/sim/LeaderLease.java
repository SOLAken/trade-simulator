package com.tradesim.market.sim;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 行情生成必须全局唯一（否则多实例会各自生成一套价格）。
 * 用 Redis 租约做轻量选主：SET NX PX 抢占，持有者每次续约用 Lua 校验 owner 后再 PEXPIRE。
 * 持有者宕机后租约过期，其他实例在下一个周期接管（RTO ≈ 租期）。
 */
@Component
public class LeaderLease {
    private static final String KEY = "mkt:leader";
    private static final Duration TTL = Duration.ofSeconds(3);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String owner = UUID.randomUUID().toString();

    public LeaderLease(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 每个 tick 调用：是主则续约，不是主则尝试抢占。 */
    public boolean tryHold() {
        Long renewed = redis.execute(RENEW, List.of(KEY), owner, Long.toString(TTL.toMillis()));
        if (renewed != null && renewed == 1L) return true;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY, owner, TTL));
    }
}
