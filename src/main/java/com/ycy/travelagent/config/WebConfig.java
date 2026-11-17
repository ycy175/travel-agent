package com.ycy.travelagent.config;

import com.ycy.travelagent.auth.AuthInterceptor;
import com.ycy.travelagent.auth.TokenService;
import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.ratelimit.RateLimitInterceptor;
import com.ycy.travelagent.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RateLimiter chatRateLimiter;

    public WebConfig(TokenService tokenService,
                     UserRepository userRepository,
                     @Qualifier("chatRateLimiter") RateLimiter chatRateLimiter) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.chatRateLimiter = chatRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(tokenService, userRepository))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");

        // 注册顺序即执行顺序：先鉴权拿到 userId，再按用户限流
        registry.addInterceptor(new RateLimitInterceptor(chatRateLimiter))
                .addPathPatterns("/api/chat/**");
    }
}
