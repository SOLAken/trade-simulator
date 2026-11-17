package com.tradesim.user;

import com.tradesim.common.auth.JwtCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

@Configuration
public class AuthConfig {
    @Bean
    JwtCodec jwtCodec(@Value("${tradesim.jwt-secret}") String secret) {
        return new JwtCodec(secret, Duration.ofDays(7));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
