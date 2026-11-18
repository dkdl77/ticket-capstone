package com.ticketing.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.ticketing.queue.LuaScripts;

@Repository
public class RedisLoginAttemptStore implements LoginAttemptStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> FAILURE = LuaScripts.plain("login-failure.lua", List.class);

    private final StringRedisTemplate redis;

    public RedisLoginAttemptStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String accountKey) {
        return "auth:fail:" + accountKey;
    }

    @Override
    public Optional<Duration> lockedFor(String accountKey, int maxFailures) {
        String v = redis.opsForValue().get(key(accountKey));
        if (v == null || Long.parseLong(v) < maxFailures) {
            return Optional.empty();
        }
        Long ttl = redis.getExpire(key(accountKey), java.util.concurrent.TimeUnit.MILLISECONDS);
        return ttl == null || ttl <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(ttl));
    }

    @Override
    public Optional<Duration> recordFailure(String accountKey, int maxFailures, Duration lockDuration) {
        List<?> r = redis.execute(FAILURE, List.of(key(accountKey)), String.valueOf(maxFailures),
                String.valueOf(lockDuration.toMillis()));
        long count = ((Number) r.get(0)).longValue();
        long ttl = ((Number) r.get(1)).longValue();
        return count >= maxFailures ? Optional.of(Duration.ofMillis(Math.max(ttl, 1000))) : Optional.empty();
    }

    @Override
    public void reset(String accountKey) {
        redis.delete(key(accountKey));
    }
}
