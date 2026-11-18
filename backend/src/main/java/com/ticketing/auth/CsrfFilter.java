package com.ticketing.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ticketing.common.ApiErrorWriter;
import com.ticketing.common.ErrorCode;

/**
 * CSRF 방어(동기화 토큰 방식). 쿠키 인증이라 다른 사이트가 브라우저를 시켜 요청을 보낼 수 있으므로,
 * GET·HEAD(·CORS 사전요청 OPTIONS) 외 모든 /api 요청은 헤더 X-CSRF-Token이 세션에 저장된 값과 같아야 한다.
 * 다른 출처의 페이지는 이 값을 읽을 수 없다(응답 body로만 주고, 쿠키로 주지 않음).
 * 비교는 상수 시간. 실패하면 403 CSRF_INVALID. 세션이 없으면(만료 포함) 역시 403.
 * Spring Session 필터(세션 → Redis) 뒤에서 실행되어야 하므로 순서를 그 뒤로 둔다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class CsrfFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-CSRF-Token";

    private final ApiErrorWriter errorWriter;

    public CsrfFilter(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        return !request.getRequestURI().startsWith("/api/")
                || "GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(SessionAuth.CSRF);
        String actual = request.getHeader(HEADER);
        if (!(expected instanceof String e) || actual == null || !constantTimeEquals(e, actual)) {
            errorWriter.write(request, response, ErrorCode.CSRF_INVALID, ErrorCode.CSRF_INVALID.defaultMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
