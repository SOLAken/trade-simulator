package com.tradesim.rank;

import com.tradesim.common.web.Headers;
import com.tradesim.common.web.Result;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/rank")
public class RankController {
    private final RankService ranks;
    private final JdbcTemplate jdbc;

    public RankController(RankService ranks, JdbcTemplate jdbc) {
        this.ranks = ranks;
        this.jdbc = jdbc;
    }

    @GetMapping("/{contestId}")
    public Result<RankService.Board> board(@RequestHeader(Headers.USER_ID) long uid, @PathVariable long contestId,
                                           @RequestParam(defaultValue = "50") int top) {
        return Result.ok(ranks.board(contestId, Math.min(top, 200), uid));
    }

    /** 自己的收益曲线：[ts, 总资产]，按 1 分钟降采样（TimescaleDB time_bucket）。 */
    @GetMapping("/{contestId}/curve")
    public Result<List<long[]>> curve(@RequestHeader(Headers.USER_ID) long uid, @PathVariable long contestId,
                                      @RequestParam(defaultValue = "24") int hours) {
        return Result.ok(jdbc.query("""
                        SELECT time_bucket('1 minute', ts) AS t, last(total, ts)
                        FROM equity WHERE contest_id = ? AND user_id = ? AND ts > ?
                        GROUP BY t ORDER BY t""",
                (rs, i) -> new long[]{rs.getTimestamp(1).getTime(), rs.getLong(2)},
                contestId, uid, Timestamp.from(Instant.now().minusSeconds(hours * 3600L))));
    }

    @Configuration
    @EnableWebSocket
    static class Ws implements WebSocketConfigurer {
        private final RankWsHandler handler;
        Ws(RankWsHandler handler) { this.handler = handler; }
        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
            r.addHandler(handler, "/ws/rank").setAllowedOriginPatterns("*");
        }
    }
}
