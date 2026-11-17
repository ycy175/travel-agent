package com.ycy.travelagent.config;

import com.ycy.travelagent.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean("chatRateLimiter")
    public RateLimiter chatRateLimiter(@Value("${app.rate-limit.chat-per-minute:10}") int perMinute) {
        return new RateLimiter(perMinute);
    }

    @Bean("loginRateLimiter")
    public RateLimiter loginRateLimiter(@Value("${app.rate-limit.login-per-minute:5}") int perMinute) {
        return new RateLimiter(perMinute);
    }
}
