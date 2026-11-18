package com.ticketing.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketing.auth.SessionAuth;

/** 대기열. v3부터 로그인 필요(401 UNAUTHENTICATED). */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    public static final String TOKEN_HEADER = "X-Queue-Token";

    public record EnterRequest(
            @NotNull(message = "회차를 선택해 주세요.") @Positive(message = "회차 번호가 올바르지 않습니다.") Long performanceId) {
    }

    private final QueueService service;
    private final SessionAuth sessions;

    public QueueController(QueueService service, SessionAuth sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping(path = "/enter", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QueueState enter(HttpServletRequest request, @Valid @RequestBody EnterRequest body) {
        return service.enter(sessions.requireUserId(request), body.performanceId());
    }

    @GetMapping("/status")
    public QueueState status(HttpServletRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return service.status(sessions.requireUserId(request), token);
    }

    @DeleteMapping
    public ResponseEntity<Void> leave(HttpServletRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        service.leave(sessions.requireUserId(request), token);
        return ResponseEntity.noContent().build();
    }
}
