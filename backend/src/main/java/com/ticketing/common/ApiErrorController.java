package com.ticketing.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketing.web.RequestIdFilter;

/**
 * 컨트롤러 밖(필터, 컨테이너)에서 난 오류가 /error로 넘어올 때도 ApiError 형태로 응답한다.
 * Spring Boot 기본 오류 응답(경로·timestamp·trace 포함 가능)을 대체한다.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status instanceof Integer i ? i : 500;
        ErrorCode errorCode = switch (code) {
            case 400 -> ErrorCode.VALIDATION_ERROR;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        return ResponseEntity.status(errorCode.status())
                .body(ApiError.of(errorCode, errorCode.defaultMessage(), RequestIdFilter.currentRequestId(request)));
    }
}
