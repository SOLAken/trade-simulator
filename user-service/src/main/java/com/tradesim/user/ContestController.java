package com.tradesim.user;

import com.tradesim.common.contest.ContestKeys;
import com.tradesim.common.web.BizException;
import com.tradesim.common.web.ErrorCode;
import com.tradesim.common.web.Headers;
import com.tradesim.common.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/contests")
public class ContestController implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final AccountClient accounts;

    public ContestController(JdbcClient jdbc, StringRedisTemplate redis, AccountClient accounts) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.accounts = accounts;
    }

    public record ContestView(long id, String name, long initCash, long startAt, long endAt, String status,
                              long members, boolean joined) {}

    public record CreateReq(@NotBlank @Size(max = 30) String name,
                            @Min(10_000) @Max(100_000_000) long initCashYuan,
                            @Min(5) @Max(60 * 24 * 30) int durationMinutes) {}

    @GetMapping
    public Result<List<ContestView>> list(@RequestHeader(Headers.USER_ID) long uid) {
        long now = System.currentTimeMillis();
        return Result.ok(jdbc.sql("""
                        SELECT c.id, c.name, c.init_cash, c.start_at, c.end_at,
                               (SELECT count(*) FROM t_contest_member m WHERE m.contest_id = c.id) AS members,
                               EXISTS(SELECT 1 FROM t_contest_member m WHERE m.contest_id = c.id AND m.user_id = ?) AS joined
                        FROM t_contest c ORDER BY c.end_at > NOW(3) DESC, c.id DESC LIMIT 50""")
                .param(uid)
                .query((rs, i) -> {
                    long start = rs.getTimestamp("start_at").getTime(), end = rs.getTimestamp("end_at").getTime();
                    String status = now < start ? "报名中" : now < end ? "进行中" : "已结束";
                    return new ContestView(rs.getLong("id"), rs.getString("name"), rs.getLong("init_cash"),
                            start, end, status, rs.getLong("members"), rs.getBoolean("joined"));
                }).list());
    }

    @PostMapping
    public Result<Long> create(@RequestHeader(Headers.USER_ID) long uid, @Valid @RequestBody CreateReq req) {
        long now = System.currentTimeMillis();
        long end = now + req.durationMinutes() * 60_000L;
        var kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO t_contest(name, init_cash, start_at, end_at, status) VALUES (?, ?, ?, ?, 1)")
                .params(req.name(), req.initCashYuan() * 100, new Timestamp(now), new Timestamp(end))
                .update(kh);
        long id = kh.getKey().longValue();
        publishMeta(id, req.name(), req.initCashYuan() * 100, now, end);
        join(uid, id);
        return Result.ok(id);
    }

    /**
     * 报名：写参赛关系 → Feign 调 account-service 开户 → 登记到排行榜成员集合。
     * 三步都幂等，任一步失败用户重试即可（无需分布式事务）。
     */
    @PostMapping("/{id}/join")
    public Result<Boolean> join(@RequestHeader(Headers.USER_ID) long uid, @PathVariable long id) {
        // Connector/J 8 的 getObject 对 DATETIME 返回 LocalDateTime，这里显式按 Timestamp 读取
        long[] c = jdbc.sql("SELECT init_cash, end_at FROM t_contest WHERE id = ?").param(id)
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getTimestamp(2).getTime()})
                .optional().orElseThrow(() -> new BizException(ErrorCode.CONTEST_NOT_FOUND));
        if (c[1] < System.currentTimeMillis()) throw new BizException(ErrorCode.CONTEST_CLOSED);
        int inserted = jdbc.sql("INSERT IGNORE INTO t_contest_member(contest_id, user_id) VALUES (?, ?)").params(id, uid).update();
        accounts.open(uid, id, c[0]);
        redis.opsForSet().add(ContestKeys.members(id), Long.toString(uid));
        return Result.ok(inserted == 1);
    }

    /** 启动时把所有未结束比赛的元数据同步到 Redis（Redis 清空后可自愈）。 */
    @Override
    public void run(ApplicationArguments args) {
        jdbc.sql("SELECT id, name, init_cash, start_at, end_at FROM t_contest WHERE end_at > NOW(3)")
                .query((rs, i) -> {
                    publishMeta(rs.getLong(1), rs.getString(2), rs.getLong(3),
                            rs.getTimestamp(4).getTime(), rs.getTimestamp(5).getTime());
                    return null;
                }).list();
        jdbc.sql("SELECT contest_id, user_id FROM t_contest_member").query((rs, i) -> {
            redis.opsForSet().add(ContestKeys.members(rs.getLong(1)), Long.toString(rs.getLong(2)));
            return null;
        }).list();
    }

    private void publishMeta(long id, String name, long initCash, long start, long end) {
        redis.opsForHash().putAll(ContestKeys.meta(id), Map.of(
                "name", name, "initCash", Long.toString(initCash),
                "startAt", Long.toString(start), "endAt", Long.toString(end)));
        redis.opsForSet().add(ContestKeys.ACTIVE, Long.toString(id));
    }
}
