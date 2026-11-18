package com.ticketing.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/** Redis 고정 창 카운터. INCR + 첫 요청 때 만료 설정을 Lua로 한 번에(원자적으로) 수행한다. */
@Repository
public class RedisRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/rate-limit.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision hit(String bucket, String clientKey, int limit, Duration window) {
        String key = "rl:" + bucket + ":" + clientKey;
        List<?> result = redis.execute(SCRIPT, List.of(key), String.valueOf(window.toMillis()));
        long count = ((Number) result.get(0)).longValue();
        long ttlMillis = Math.max(((Number) result.get(1)).longValue(), 0);
        boolean allowed = count <= limit;
        int remaining = (int) Math.max(limit - count, 0);
        long retryAfter = Math.max(1, (ttlMillis + 999) / 1000);
        return new Decision(allowed, remaining, retryAfter);
    }
}
