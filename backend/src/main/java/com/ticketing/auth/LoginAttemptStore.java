package com.ticketing.auth;

import java.time.Duration;
import java.util.Optional;

/**
 * 계정별 연속 로그인 실패 횟수(Redis). 키는 이메일 원문이 아니라 SHA-256(정규화 이메일).
 * 없는 이메일에도 똑같이 적용된다 → 잠금 여부로 가입 여부를 알 수 없다.
 */
public interface LoginAttemptStore {

    /** 잠겨 있으면 남은 시간. */
    Optional<Duration> lockedFor(String accountKey, int maxFailures);

    /** 실패 1회 기록. 이번 실패로 잠겼으면 잠금 시간을 돌려준다. */
    Optional<Duration> recordFailure(String accountKey, int maxFailures, Duration lockDuration);

    void reset(String accountKey);
}
