package com.tradesim.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.contest.RiskKeys;
import com.tradesim.common.web.Headers;
import com.tradesim.common.web.Result;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/risk")
public class RiskController {
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final RiskRules rules;

    public RiskController(StringRedisTemplate redis, ObjectMapper om, RiskRules rules) {
        this.redis = redis;
        this.om = om;
        this.rules = rules;
    }

    public record Status(boolean blocked, String reason, long secondsLeft) {}

    @GetMapping("/me")
    public Result<Status> me(@RequestHeader(Headers.USER_ID) long uid) {
        String reason = redis.opsForValue().get(RiskKeys.block(uid));
        Long ttl = redis.getExpire(RiskKeys.block(uid));
        return Result.ok(new Status(reason != null, reason, ttl == null || ttl < 0 ? 0 : ttl));
    }

    @GetMapping("/alerts")
    public Result<List<JsonNode>> alerts(@RequestParam(defaultValue = "50") int limit) {
        List<String> raw = Optional.ofNullable(redis.opsForList().range(RiskKeys.ALERTS, 0, Math.min(limit, 200) - 1))
                .orElse(List.of());
        List<JsonNode> out = new ArrayList<>();
        for (String s : raw) {
            try { out.add(om.readTree(s)); } catch (Exception ignored) { }
        }
        return Result.ok(out);
    }

    @GetMapping("/rules")
    public Result<RiskRules> rules() {
        return Result.ok(rules);
    }
}
