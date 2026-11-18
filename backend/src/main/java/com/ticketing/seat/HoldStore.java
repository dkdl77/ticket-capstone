package com.ticketing.seat;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 좌석 임시 선점(Redis). 좌석마다 키를 두고 TTL로 자동 해제된다(정리 작업 없음).
 * 한 토큰은 선점 1건만 가진다. 새로 선점하면 이전 선점은 같은 스크립트 안에서 해제된다.
 */
public interface HoldStore {

    record Hold(String holdId, String tokenId, long userId, long performanceId, List<Long> seatIds, int totalPrice,
                Instant expiresAt) {
    }

    sealed interface Result permits Created, Taken, AdmissionEnding {
    }

    record Created(Hold hold) implements Result {
    }

    /** 이미 다른 선점이 잡고 있는 좌석. */
    record Taken(List<Long> seatIds) implements Result {
    }

    /** 입장 시간이 거의 끝나 선점할 수 없다. */
    record AdmissionEnding() implements Result {
    }

    /** 전부 아니면 전무. 만료 = min(지금 + ttl, notAfter). */
    Result create(String tokenId, long userId, long performanceId, List<Long> seatIds, int totalPrice, Duration ttl,
            Instant notAfter);

    Optional<Hold> find(String holdId);

    Optional<Hold> currentOf(String tokenId);

    /** seatId → 선점 holdId (선점 중인 좌석만). 좌석 화면 폴링용이라 MGET 한 번이다. */
    Map<Long, String> holdersOf(long performanceId, Collection<Long> seatIds);

    /** 선점한 토큰일 때만 해제. 해제했으면 true. */
    boolean release(String holdId, String tokenId);

    void releaseCurrent(String tokenId);
}
