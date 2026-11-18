package com.ticketing.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * /readyz 판단: 종료 중이 아니고, MySQL과 Redis 둘 다 응답해야 준비 완료.
 * Actuator를 넣지 않고 Spring Boot 기본 ApplicationAvailability(종료 시작 시 REFUSING_TRAFFIC)를 쓴다.
 */
@Service
public class ReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final ApplicationAvailability availability;

    public ReadinessService(JdbcClient jdbc, StringRedisTemplate redis, ApplicationAvailability availability) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.availability = availability;
    }

    public boolean isReady() {
        return availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC && mysqlUp() && redisUp();
    }

    private boolean mysqlUp() {
        try {
            Integer one = jdbc.sql("SELECT 1").query(Integer.class).single();
            return one != null && one == 1;
        } catch (RuntimeException e) {
            log.warn("MySQL 연결 확인 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean redisUp() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException e) {
            log.warn("Redis 연결 확인 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
