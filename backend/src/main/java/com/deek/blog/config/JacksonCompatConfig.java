package com.deek.blog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 4 默认使用 Jackson 3 处理 MVC，而 JJWT / 既有 AI、GitHub 服务仍使用 Jackson 2。
 * 这里显式提供一个 Jackson 2 ObjectMapper，避免两套 JSON 栈互相覆盖。
 */
@Configuration
public class JacksonCompatConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
