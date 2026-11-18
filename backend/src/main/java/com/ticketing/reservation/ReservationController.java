package com.ticketing.reservation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ticketing.auth.SessionAuth;
import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.queue.QueueController;

/** 예매 확정(로그인 필요)과 내 예매. v2의 비회원 조회·취소(/api/reservations/lookup, /cancel)는 없어졌다. */
@RestController
public class ReservationController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public record MyReservations(List<ReservationView> reservations) {
    }

    private final ReservationService service;
    private final SessionAuth sessions;

    public ReservationController(ReservationService service, SessionAuth sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping(path = "/api/reservations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationView> create(HttpServletRequest request,
            @RequestHeader(value = QueueController.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest body) {
        long userId = sessions.requireUserId(request);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key 헤더가 필요합니다.");
        }
        if (!UUID.matcher(idempotencyKey).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key 헤더는 UUID 형식이어야 합니다.");
        }
        ReservationView view = service.confirm(userId, token, idempotencyKey.toLowerCase(Locale.ROOT), body.holdId());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/api/me/reservations")
    public MyReservations mine(HttpServletRequest request) {
        return new MyReservations(service.myReservations(sessions.requireUserId(request)));
    }

    @PostMapping("/api/me/reservations/{code}/cancel")
    public ReservationView cancel(HttpServletRequest request, @PathVariable String code) {
        return service.cancel(sessions.requireUserId(request), code);
    }
}
