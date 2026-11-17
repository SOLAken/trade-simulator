package com.tradesim.user;

import com.tradesim.common.auth.JwtCodec;
import com.tradesim.common.web.BizException;
import com.tradesim.common.web.ErrorCode;
import com.tradesim.common.web.Headers;
import com.tradesim.common.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtCodec jwt;

    public UserController(JdbcClient jdbc, PasswordEncoder encoder, JwtCodec jwt) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record RegisterReq(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$", message = "须为 3-20 位字母数字下划线") String username,
            @NotBlank @Size(min = 6, max = 64) String password,
            @Size(max = 20) String nickname) {}

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    public record LoginResp(long userId, String username, String nickname, String token) {}

    @PostMapping("/register")
    public Result<LoginResp> register(@Valid @RequestBody RegisterReq req) {
        String nick = req.nickname() == null || req.nickname().isBlank() ? req.username() : req.nickname();
        var kh = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO t_user(username, password_hash, nickname) VALUES (?, ?, ?)")
                    .params(req.username(), encoder.encode(req.password()), nick)
                    .update(kh);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已存在");
        }
        long id = kh.getKey().longValue();
        return Result.ok(new LoginResp(id, req.username(), nick, jwt.sign(id, req.username())));
    }

    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req) {
        var row = jdbc.sql("SELECT id, password_hash, nickname FROM t_user WHERE username = ?")
                .param(req.username())
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)})
                .optional()
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        if (!encoder.matches(req.password(), (String) row[1])) throw new BizException(ErrorCode.UNAUTHORIZED);
        long id = (Long) row[0];
        return Result.ok(new LoginResp(id, req.username(), (String) row[2], jwt.sign(id, req.username())));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(Headers.USER_ID) long userId) {
        return Result.ok(jdbc.sql("SELECT id, username, nickname, created_at FROM t_user WHERE id = ?")
                .param(userId).query().singleRow());
    }
}
