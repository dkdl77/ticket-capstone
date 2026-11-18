package com.ticketing.common;

import org.springframework.http.HttpStatus;

/** 계약(ARCHITECTURE.md v3 4.2)의 오류 코드와 사용자에게 보여줄 기본 문구. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값을 다시 확인해 주세요."),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "공연을 찾을 수 없습니다."),
    PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."),
    NOT_ON_SALE(HttpStatus.CONFLICT, "지금은 예매할 수 없는 회차입니다."),
    SOLD_OUT(HttpStatus.CONFLICT, "매진된 회차입니다."),
    QUEUE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "대기 시간이 만료되었습니다. 예매하기를 다시 눌러 주세요."),
    QUEUE_NOT_ADMITTED(HttpStatus.FORBIDDEN, "아직 입장 순서가 아닙니다. 대기 화면에서 잠시만 기다려 주세요."),
    SEAT_TAKEN(HttpStatus.CONFLICT, "선택한 좌석 중 이미 선택되었거나 판매된 좌석이 있습니다. 다른 좌석을 골라 주세요."),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "선택한 좌석 정보를 찾을 수 없습니다."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "좌석 선택 시간이 지났습니다. 좌석을 다시 골라 주세요."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예매 내역을 찾을 수 없습니다."),
    ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 예매입니다."),
    PERFORMANCE_CLOSED(HttpStatus.CONFLICT, "이미 시작했거나 종료된 회차는 취소할 수 없습니다."),
    // v3 인증
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인에 여러 번 실패해 잠시 잠겼습니다. 잠시 후 다시 시도해 주세요."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 없거나 만료되었습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요."),
    BOOKING_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "한 회차에는 계정당 최대 4석까지 예매할 수 있습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

    // ↓ 계약 4.2에 없는 보조 코드(계약 변경 제안으로 보고). 정상적인 UI 흐름에서는 나오지 않는다.
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "같은 예매 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "요청 본문이 너무 큽니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 방식입니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
