package com.ticketing.seat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.ticketing.queue.LuaScripts;
import com.ticketing.queue.QueueTokens;
import com.ticketing.queue.RedisKeys;

@Repository
public class RedisHoldStore implements HoldStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CREATE = LuaScripts.plain("hold-create.lua", List.class);
    private static final RedisScript<Long> RELEASE = LuaScripts.plain("hold-release.lua", Long.class);
    private static final String NO_HOLD_KEY = "hold:none";
    private static final int MAX_ATTEMPTS = 3;

    private final StringRedisTemplate redis;
    private final QueueTokens tokens;

    public RedisHoldStore(StringRedisTemplate redis, QueueTokens tokens) {
        this.redis = redis;
        this.tokens = tokens;
    }

    @Override
    public Result create(String tokenId, long userId, long performanceId, List<Long> seatIds, int totalPrice, Duration ttl,
            Instant notAfter) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String prevId = currentHoldId(tokenId);
            Optional<Hold> prev = prevId == null ? Optional.empty() : find(prevId);
            String newId = tokens.newHoldId();

            List<String> keys = new ArrayList<>();
            keys.add(RedisKeys.token(tokenId));
            keys.add(RedisKeys.hold(newId));
            keys.add(prevId == null ? NO_HOLD_KEY : RedisKeys.hold(prevId));
            seatIds.forEach(id -> keys.add(RedisKeys.seatHold(performanceId, id)));
            prev.ifPresent(p -> p.seatIds().forEach(id -> keys.add(RedisKeys.seatHold(p.performanceId(), id))));

            List<?> r = redis.execute(CREATE, keys,
                    newId, prevId == null ? "" : prevId, String.valueOf(ttl.toMillis()),
                    String.valueOf(seatIds.size()), csv(seatIds), String.valueOf(totalPrice),
                    String.valueOf(performanceId), tokenId,
                    String.valueOf(notAfter == null ? 0 : notAfter.toEpochMilli()), String.valueOf(userId));
            long kind = ((Number) r.get(0)).longValue();
            if (kind == 1) {
                Instant expires = Instant.ofEpochMilli(((Number) r.get(1)).longValue());
                return new Created(new Hold(newId, tokenId, userId, performanceId, List.copyOf(seatIds), totalPrice, expires));
            }
            if (kind == 0) {
                List<Long> taken = r.stream().skip(1)
                        .map(i -> seatIds.get(((Number) i).intValue() - 1)).toList();
                return new Taken(taken);
            }
            if (kind == -2) {
                return new AdmissionEnding();
            }
            // -1: 그사이 이전 선점이 바뀜(같은 토큰의 동시 요청) → 다시 읽고 재시도
        }
        throw new IllegalStateException("선점 상태가 계속 바뀌어 처리하지 못했습니다.");
    }

    @Override
    public Optional<Hold> find(String holdId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.hold(holdId));
        if (h == null || h.isEmpty()) {
            return Optional.empty();
        }
        List<Long> seats = Arrays.stream(h.get("seats").toString().split(",")).map(Long::valueOf).toList();
        Object uid = h.get("uid");
        return Optional.of(new Hold(holdId, h.get("token").toString(), uid == null ? -1L : Long.parseLong(uid.toString()), Long.parseLong(h.get("perf").toString()), seats,
                Integer.parseInt(h.get("total").toString()),
                Instant.ofEpochMilli((long) Double.parseDouble(h.get("expiresAt").toString()))));
    }

    @Override
    public Optional<Hold> currentOf(String tokenId) {
        String id = currentHoldId(tokenId);
        return id == null ? Optional.empty() : find(id).filter(h -> h.tokenId().equals(tokenId));
    }

    @Override
    public Map<Long, String> holdersOf(long performanceId, Collection<Long> seatIds) {
        List<Long> ids = List.copyOf(seatIds);
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<String> values = redis.opsForValue()
                .multiGet(ids.stream().map(id -> RedisKeys.seatHold(performanceId, id)).toList());
        if (values != null) {
            for (int i = 0; i < ids.size(); i++) {
                if (values.get(i) != null) {
                    out.put(ids.get(i), values.get(i));
                }
            }
        }
        return out;
    }

    @Override
    public boolean release(String holdId, String tokenId) {
        Optional<Hold> hold = find(holdId);
        if (hold.isEmpty() || !hold.get().tokenId().equals(tokenId)) {
            return false;
        }
        Hold h = hold.get();
        List<String> keys = new ArrayList<>();
        keys.add(RedisKeys.hold(holdId));
        keys.add(RedisKeys.token(tokenId));
        h.seatIds().forEach(id -> keys.add(RedisKeys.seatHold(h.performanceId(), id)));
        Long r = redis.execute(RELEASE, keys, holdId, tokenId);
        return r != null && r == 1L;
    }

    @Override
    public void releaseCurrent(String tokenId) {
        String id = currentHoldId(tokenId);
        if (id != null) {
            release(id, tokenId);
        }
    }

    private String currentHoldId(String tokenId) {
        Object v = redis.opsForHash().get(RedisKeys.token(tokenId), "hold");
        return v == null ? null : v.toString();
    }

    private static String csv(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
