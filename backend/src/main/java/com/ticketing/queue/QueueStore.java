package com.ticketing.queue;

import java.time.Instant;
import java.util.Optional;

import com.ticketing.config.AppProperties;

/**
 * 회차별 접속 대기열(Redis). 상태 전진(만료 정리·승격)은 Lua 스크립트가 요청 시점에 원자적으로 수행한다.
 * tokenId는 토큰 원문이 아니라 SHA-256 값이다(Redis에 원문 토큰을 남기지 않음).
 * v3: 토큰은 발급받은 사용자(userId)에게 묶인다.
 */
public interface QueueStore {

    enum State { WAITING, ADMITTED }

    /** position = 내 앞에 남은 인원(ADMITTED면 0). admittedUntil은 ADMITTED일 때만. */
    record TokenState(long performanceId, long userId, State state, long position, Instant admittedUntil) {
    }

    TokenState enter(long performanceId, long userId, String tokenId, AppProperties.Queue settings);

    /** 이 사용자가 이 회차에서 이미 가진 토큰(있으면). */
    Optional<String> tokenOf(long performanceId, long userId);

    /** 대기 상태 폴링: 폴링 시각 갱신 + 상태 전진. 비면 토큰 무효(만료·이탈·없음). */
    Optional<TokenState> poll(String tokenId, AppProperties.Queue settings);

    /** 상태를 바꾸지 않는 확인(좌석 조회·선점·확정용). 입장 만료면 비어 있다. */
    Optional<TokenState> peek(String tokenId);

    /** 이탈 또는 예매 완료: 토큰을 지우고 다음 대기자를 승격. 없는 토큰이어도 조용히 끝난다. */
    void leave(String tokenId, AppProperties.Queue settings);
}
