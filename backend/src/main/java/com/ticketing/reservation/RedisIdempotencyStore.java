package com.ticketing.reservation;

import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "idem:reservation:";
    private static final String PENDING = "PENDING";
    private static final Duration PENDING_TTL = Duration.ofSeconds(60);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Claim claim(String key) {
        String k = PREFIX + key;
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(k, PENDING, PENDING_TTL))) {
            return new Acquired();
        }
        String value = redis.opsForValue().get(k);
        if (value == null || PENDING.equals(value)) {
            // null: 방금 풀렸음 → 호출자가 잠시 후 다시 claim 한다.
            return new InProgress();
        }
        try {
            return new Completed(objectMapper.readValue(value, ReservationView.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 멱등 응답을 읽을 수 없습니다.", e);
        }
    }

    @Override
    public void complete(String key, ReservationView response) {
        try {
            redis.opsForValue().set(PREFIX + key, objectMapper.writeValueAsString(response), COMPLETED_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 응답을 저장할 수 없습니다.", e);
        }
    }

    @Override
    public void release(String key) {
        redis.delete(PREFIX + key);
    }
}
