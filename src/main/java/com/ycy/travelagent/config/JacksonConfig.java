package com.ycy.travelagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 的 HTTP 消息转换默认走 Jackson 3，不再自动暴露
 * com.fasterxml.jackson.databind.ObjectMapper。
 * 项目里 SSE / token 需要手写 JSON，这里显式提供一个 Jackson 2 实例。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
