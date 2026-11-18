package com.ticketing.common;

/**
 * 업무 규칙 위반. 전역 예외 처리기가 계약의 ApiError 형태로 바꾼다. 스택 추적은 만들지 않는다(흔한 실패라 비용 절약).
 * details는 계약의 선택 필드(예: SEAT_TAKEN → { seatIds: [...] }).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Object details;
    private Long retryAfterSeconds;

    /** 응답에 Retry-After 헤더를 붙인다(예: ACCOUNT_LOCKED). */
    public ApiException retryAfter(long seconds) {
        this.retryAfterSeconds = Math.max(1, seconds);
        return this;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Object details) {
        super(message, null, false, false);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
