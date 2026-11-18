package com.ticketing.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS는 기본 비활성(Ingress 경로 분기로 같은 출처). CORS_ALLOWED_ORIGINS가 있을 때만 그 출처를 허용한다.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = props.cors().origins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Content-Type", "Idempotency-Key", "X-Queue-Token")
                .exposedHeaders("X-Request-Id", "Retry-After", "RateLimit-Limit", "RateLimit-Remaining")
                .allowCredentials(false)
                .maxAge(600);
    }
}
