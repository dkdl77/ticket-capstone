package com.ticketing.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 계약의 오류 응답: {@code { "error": { "code", "message", "requestId", "details"? } }} */
public record ApiError(Body error) {

    public record Body(
            String code,
            String message,
            String requestId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Object details) {
    }

    /** VALIDATION_ERROR의 details 항목. */
    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String message, String requestId) {
        return of(code, message, requestId, null);
    }

    public static ApiError of(ErrorCode code, String message, String requestId, Object details) {
        return new ApiError(new Body(code.name(), message, requestId, details));
    }
}
