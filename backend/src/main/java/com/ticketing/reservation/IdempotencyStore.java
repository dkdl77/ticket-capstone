package com.ticketing.reservation;

/**
 * Idempotency-Key 저장소(Redis, 24시간).
 * 상태: 없음 → 처리 중(PENDING, 60초) → 완료(첫 201 응답 저장, 24시간).
 * 업무 실패(매진 등)는 저장하지 않고 키를 풀어 준다. 부작용이 없었으니 같은 키로 다시 시도해도 안전하다.
 */
public interface IdempotencyStore {

    sealed interface Claim permits Acquired, InProgress, Completed {
    }

    /** 이 요청이 처리 권한을 얻었다. */
    record Acquired() implements Claim {
    }

    /** 같은 키의 다른 요청이 처리 중이다. */
    record InProgress() implements Claim {
    }

    /** 이미 처리된 키. 처음 응답을 그대로 돌려준다. */
    record Completed(ReservationView response) implements Claim {
    }

    Claim claim(String key);

    void complete(String key, ReservationView response);

    void release(String key);
}
