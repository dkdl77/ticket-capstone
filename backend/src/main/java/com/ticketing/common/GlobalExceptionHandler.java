package com.ticketing.common;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ticketing.web.PayloadTooLargeException;
import com.ticketing.web.RequestIdFilter;

/**
 * 모든 예외를 계약의 ApiError 형태로 통일한다.
 * 스택·SQL·내부 경로는 응답에 넣지 않는다. 5xx는 고정 문구 + 요청 ID, 자세한 내용은 서버 로그에만 남긴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern SAFE_FIELD = Pattern.compile("^[A-Za-z0-9_]{1,40}$");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest req) {
        ResponseEntity<ApiError> response = respond(req, e.code(), e.getMessage(), e.details());
        if (e.retryAfterSeconds() != null) {
            return ResponseEntity.status(response.getStatusCode())
                    .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        // details: 입력칸 옆에 표시할 수 있도록 필드별 메시지 목록(계약의 선택 필드)
        List<ApiError.FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(f -> new ApiError.FieldViolation(f.getField(), f.getDefaultMessage()))
                .toList();
        String message = violations.isEmpty()
                ? ErrorCode.VALIDATION_ERROR.defaultMessage() : violations.get(0).message();
        return respond(req, ErrorCode.VALIDATION_ERROR, message, violations.isEmpty() ? null : violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof PayloadTooLargeException) {
                return respond(req, ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.defaultMessage());
            }
            cause = cause.getCause();
        }
        return respond(req, ErrorCode.VALIDATION_ERROR, describe(e.getCause()));
    }

    private static String describe(Throwable cause) {
        if (cause instanceof UnrecognizedPropertyException u) {
            String name = u.getPropertyName();
            return name != null && SAFE_FIELD.matcher(name).matches()
                    ? "정의되지 않은 필드는 보낼 수 없습니다: " + name
                    : "정의되지 않은 필드는 보낼 수 없습니다.";
        }
        if (cause instanceof MismatchedInputException m) {
            String field = m.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(f -> f != null && SAFE_FIELD.matcher(f).matches())
                    .reduce((a, b) -> b)
                    .orElse(null);
            return field != null ? "'" + field + "' 값의 형식이 올바르지 않습니다." : "요청 본문의 형식이 올바르지 않습니다.";
        }
        if (cause instanceof JsonProcessingException) {
            return "JSON 형식이 올바르지 않습니다.";
        }
        return "요청 본문이 비어 있거나 읽을 수 없습니다.";
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_ERROR, e.getHeaderName() + " 헤더가 필요합니다.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_ERROR, "경로 값의 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_ERROR, "Content-Type은 application/json이어야 합니다.");
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> handleNoHandler(Exception e, HttpServletRequest req) {
        return respond(req, ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return respond(req, ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest req) {
        // 요청 ID는 MDC로 로그에 함께 찍힌다. 응답에는 고정 문구만.
        log.error("처리되지 않은 예외: {} {}", req.getMethod(), req.getRequestURI(), e);
        return respond(req, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private static ResponseEntity<ApiError> respond(HttpServletRequest req, ErrorCode code, String message) {
        return respond(req, code, message, null);
    }

    private static ResponseEntity<ApiError> respond(HttpServletRequest req, ErrorCode code, String message,
            Object details) {
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, message, RequestIdFilter.currentRequestId(req), details));
    }
}
