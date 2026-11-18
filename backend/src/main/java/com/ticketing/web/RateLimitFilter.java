package com.ticketing.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ticketing.common.ApiErrorWriter;
import com.ticketing.common.ErrorCode;
import com.ticketing.config.AppProperties;
import com.ticketing.ratelimit.RateLimiter;

/**
 * IP당 요청 제한(Redis 고정 창, 1분).
 * - strict: 가입·로그인·대기열 진입·선점·선점 해제·예매 확정·취소 — 무차별 대입·좌석 독점 방지
 * - poll: 대기 상태·좌석 조회(폴링 엔드포인트) / general: 그 밖의 /api/*
 * - /healthz, /readyz 는 제한하지 않는다(프로브가 막히면 안 됨).
 * 클라이언트 IP는 request.getRemoteAddr(). server.forward-headers-strategy=native 이므로
 * 신뢰하는 내부 프록시(사설 대역: ALB/Ingress)가 붙인 X-Forwarded-For만 반영된다.
 * Redis 장애 시에는 요청을 막지 않는다(fail-open). 예매 자체가 Redis를 쓰므로 그때는 어차피 실패한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Set<String> STRICT_POST_PATHS = Set.of(
            "/api/queue/enter", "/api/reservations", "/api/auth/signup", "/api/auth/login");
    private static final Pattern HOLDS_PATH = Pattern.compile("^/api/performances/\\d+/holds$");
    private static final Pattern RELEASE_PATH = Pattern.compile("^/api/holds/[^/]+$");
    private static final Pattern MY_CANCEL_PATH = Pattern.compile("^/api/me/reservations/[^/]+/cancel$");
    private static final Pattern SEATS_PATH = Pattern.compile("^/api/performances/\\d+/seats$");

    /** 버킷 분류. poll은 정상적인 폴링(대기 2~5초, 좌석 3~5초)이 429를 맞지 않도록 따로 센다. */
    static String bucketOf(String method, String uri) {
        if ("POST".equals(method) && (STRICT_POST_PATHS.contains(uri) || HOLDS_PATH.matcher(uri).matches()
                || MY_CANCEL_PATH.matcher(uri).matches())) {
            return "strict";
        }
        if ("DELETE".equals(method) && RELEASE_PATH.matcher(uri).matches()) {
            return "strict";
        }
        if ("GET".equals(method) && ("/api/queue/status".equals(uri) || SEATS_PATH.matcher(uri).matches())) {
            return "poll";
        }
        return "general";
    }

    private final RateLimiter rateLimiter;
    private final AppProperties.RateLimit limits;
    private final ApiErrorWriter errorWriter;

    public RateLimitFilter(RateLimiter rateLimiter, AppProperties props, ApiErrorWriter errorWriter) {
        this.rateLimiter = rateLimiter;
        this.limits = props.rateLimit();
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bucket = bucketOf(request.getMethod(), request.getRequestURI());
        int limit = switch (bucket) {
            case "strict" -> limits.strictPerMinute();
            case "poll" -> limits.pollPerMinute();
            default -> limits.generalPerMinute();
        };

        RateLimiter.Decision decision;
        try {
            decision = rateLimiter.hit(bucket, request.getRemoteAddr(), limit, WINDOW);
        } catch (DataAccessException e) {
            log.warn("요청 제한 확인 실패(Redis). 요청을 통과시킵니다: {}", e.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            errorWriter.write(request, response, ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage());
            return;
        }
        chain.doFilter(request, response);
    }
}
