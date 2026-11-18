package com.ticketing.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 ID를 붙인다(들어온 X-Request-Id가 안전한 형식이면 이어받음).
 * - MDC에 넣어 모든 로그 줄에 requestId가 찍히게 하고, 응답 헤더와 오류 body에도 넣는다.
 * - 요청이 끝나면 접근 로그 한 줄(JSON)을 남긴다. 이름·이메일·쿼리스트링·IP는 남기지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final String MDC_KEY = "requestId";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Logger accessLog = LoggerFactory.getLogger("access");

    public static String currentRequestId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        if (id instanceof String s) {
            return s;
        }
        String fromMdc = MDC.get(MDC_KEY);
        return fromMdc != null ? fromMdc : "unknown";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String path = request.getRequestURI();
            if (!path.equals("/healthz") && !path.equals("/readyz")) {
                accessLog.atInfo()
                        .addKeyValue("http.method", request.getMethod())
                        .addKeyValue("url.path", path)
                        .addKeyValue("http.status", response.getStatus())
                        .addKeyValue("durationMs", ms)
                        .log("request completed");
            }
            MDC.remove(MDC_KEY);
        }
    }
}
