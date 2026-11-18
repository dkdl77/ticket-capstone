package com.ticketing.queue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.ticketing.config.AppProperties;

@Repository
public class RedisQueueStore implements QueueStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ENTER = LuaScripts.queue("queue-enter.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> POLL = LuaScripts.queue("queue-poll.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> PEEK = LuaScripts.queue("queue-peek.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> LEAVE = LuaScripts.queue("queue-leave.lua");

    private record Owner(long performanceId, long userId) {
    }

    private final StringRedisTemplate redis;

    public RedisQueueStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 토큰 정보 키의 수명: 입장 시간 + 대기 TTL + 여유 1분(폴링할 때마다 갱신). */
    private static String tokenTtlMs(AppProperties.Queue s) {
        return String.valueOf(s.admit().plus(s.waitTtl()).toMillis() + 60_000);
    }

    @Override
    public TokenState enter(long performanceId, long userId, String tokenId, AppProperties.Queue s) {
        List<?> r = redis.execute(ENTER,
                List.of(RedisKeys.waiting(performanceId), RedisKeys.seen(performanceId),
                        RedisKeys.active(performanceId), RedisKeys.seq(performanceId), RedisKeys.token(tokenId),
                        RedisKeys.userToken(performanceId, userId)),
                String.valueOf(s.maxActive()), String.valueOf(s.admit().toMillis()),
                String.valueOf(s.waitTtl().toMillis()), tokenId, String.valueOf(performanceId), tokenTtlMs(s),
                String.valueOf(userId));
        return toState(new Owner(performanceId, userId), r).orElseThrow();
    }

    @Override
    public Optional<String> tokenOf(long performanceId, long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.userToken(performanceId, userId)));
    }

    @Override
    public Optional<TokenState> poll(String tokenId, AppProperties.Queue s) {
        Owner o = ownerOf(tokenId);
        if (o == null) {
            return Optional.empty();
        }
        long perf = o.performanceId();
        List<?> r = redis.execute(POLL,
                List.of(RedisKeys.waiting(perf), RedisKeys.seen(perf), RedisKeys.active(perf), RedisKeys.token(tokenId),
                        RedisKeys.userToken(perf, o.userId())),
                String.valueOf(s.maxActive()), String.valueOf(s.admit().toMillis()),
                String.valueOf(s.waitTtl().toMillis()), tokenId, tokenTtlMs(s));
        return toState(o, r);
    }

    @Override
    public Optional<TokenState> peek(String tokenId) {
        Owner o = ownerOf(tokenId);
        if (o == null) {
            return Optional.empty();
        }
        List<?> r = redis.execute(PEEK,
                List.of(RedisKeys.waiting(o.performanceId()), RedisKeys.active(o.performanceId())), tokenId);
        return toState(o, r);
    }

    @Override
    public void leave(String tokenId, AppProperties.Queue s) {
        Owner o = ownerOf(tokenId);
        if (o == null) {
            return;
        }
        long perf = o.performanceId();
        redis.execute(LEAVE,
                List.of(RedisKeys.waiting(perf), RedisKeys.seen(perf), RedisKeys.active(perf), RedisKeys.token(tokenId),
                        RedisKeys.userToken(perf, o.userId())),
                String.valueOf(s.maxActive()), String.valueOf(s.admit().toMillis()),
                String.valueOf(s.waitTtl().toMillis()), tokenId);
    }

    private Owner ownerOf(String tokenId) {
        List<Object> v = redis.opsForHash().multiGet(RedisKeys.token(tokenId), List.of("perf", "uid"));
        if (v == null || v.get(0) == null || v.get(1) == null) {
            return null;
        }
        return new Owner(Long.parseLong(v.get(0).toString()), Long.parseLong(v.get(1).toString()));
    }

    private static Optional<TokenState> toState(Owner o, List<?> r) {
        long kind = ((Number) r.get(0)).longValue();
        if (kind == 1) {
            return Optional.of(new TokenState(o.performanceId(), o.userId(), State.ADMITTED, 0,
                    Instant.ofEpochMilli(((Number) r.get(2)).longValue())));
        }
        if (kind == 0) {
            return Optional.of(new TokenState(o.performanceId(), o.userId(), State.WAITING,
                    ((Number) r.get(1)).longValue(), null));
        }
        return Optional.empty();
    }
}
