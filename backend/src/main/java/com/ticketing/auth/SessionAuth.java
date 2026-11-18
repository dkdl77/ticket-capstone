package com.ticketing.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;

import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;

/**
 * 세션(Spring Session → Redis)에 무엇을 넣고 꺼내는지 한곳에 모았다. 세션에는 두 값만 둔다:
 * - uid: 로그인한 사용자 id(Long)
 * - csrf: CSRF 토큰(String, 256비트 난수)
 * 세션 ID·CSRF 토큰은 로그에 남기지 않는다.
 */
@Component
public class SessionAuth {

    public static final String USER_ID = "uid";
    public static final String CSRF = "csrf";

    private final SecureRandom random = new SecureRandom();

    /** 로그인한 사용자 id. 세션을 새로 만들지 않는다. */
    public Optional<Long> userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object uid = session.getAttribute(USER_ID);
        return uid instanceof Long l ? Optional.of(l) : Optional.empty();
    }

    public long requireUserId(HttpServletRequest request) {
        return userId(request).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    /** CSRF 토큰. 세션이 없으면 익명 세션을 만들어 발급한다(가입·로그인 요청도 보호하기 위해). */
    public String csrfToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object token = session.getAttribute(CSRF);
        if (token instanceof String s) {
            return s;
        }
        String fresh = newToken();
        session.setAttribute(CSRF, fresh);
        return fresh;
    }

    /**
     * 로그인 처리: 세션 ID 재발급(세션 고정 공격 방지) → 사용자 id 저장 → CSRF 토큰 새로 발급.
     * 반환값은 새 CSRF 토큰.
     */
    public String login(HttpServletRequest request, long userId) {
        request.getSession(true);
        request.changeSessionId();
        HttpSession session = request.getSession(false);
        session.setAttribute(USER_ID, userId);
        String csrf = newToken();
        session.setAttribute(CSRF, csrf);
        return csrf;
    }

    /** 세션을 서버(Redis)에서 지운다. */
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** 세션에서 사용자 정보만 지운다(계정이 사라진 경우 등). */
    public void forgetUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(USER_ID);
        }
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
