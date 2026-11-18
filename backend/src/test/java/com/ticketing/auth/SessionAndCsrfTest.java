package com.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import com.ticketing.common.ApiErrorWriter;

class SessionAndCsrfTest {

    private final SessionAuth sessions = new SessionAuth();
    private final CsrfFilter filter = new CsrfFilter(new ApiErrorWriter(new ObjectMapper().registerModule(new JavaTimeModule())));

    private MockHttpServletResponse run(MockHttpServletRequest req) throws ServletException, IOException {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        if (chain.getRequest() != null) {
            res.setStatus(200); // 통과
        }
        return res;
    }

    private static MockHttpServletRequest post(MockHttpSession session, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/queue/enter");
        if (session != null) {
            r.setSession(session);
        }
        if (token != null) {
            r.addHeader(CsrfFilter.HEADER, token);
        }
        return r;
    }

    @Test
    void 익명_세션에도_CSRF_토큰을_발급하고_같은_세션에서는_같은_값() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/auth/me");
        String t1 = sessions.csrfToken(r);
        MockHttpServletRequest r2 = new MockHttpServletRequest("GET", "/api/auth/me");
        r2.setSession(r.getSession());
        assertThat(sessions.csrfToken(r2)).isEqualTo(t1).hasSize(43);
        assertThat(sessions.userId(r2)).isEmpty();
    }

    @Test
    void 상태_변경_요청은_세션의_토큰과_같은_헤더가_있어야_통과() throws Exception {
        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/auth/me");
        String token = sessions.csrfToken(me);
        MockHttpSession session = (MockHttpSession) me.getSession();

        assertThat(run(post(session, token)).getStatus()).isEqualTo(200);
        MockHttpServletResponse noHeader = run(post(session, null));
        assertThat(noHeader.getStatus()).isEqualTo(403);
        assertThat(noHeader.getContentAsString()).contains("CSRF_INVALID");
        assertThat(run(post(session, token + "x")).getStatus()).isEqualTo(403);
        assertThat(run(post(null, token)).getStatus()).isEqualTo(403); // 세션 없음(만료)
    }

    @Test
    void GET과_HEAD와_api_밖은_검사하지_않는다() throws Exception {
        assertThat(run(new MockHttpServletRequest("GET", "/api/shows")).getStatus()).isEqualTo(200);
        assertThat(run(new MockHttpServletRequest("HEAD", "/api/shows")).getStatus()).isEqualTo(200);
        assertThat(run(new MockHttpServletRequest("POST", "/healthz")).getStatus()).isEqualTo(200);
        assertThat(run(new MockHttpServletRequest("DELETE", "/api/queue")).getStatus()).isEqualTo(403);
    }

    @Test
    void 로그인하면_세션_ID와_CSRF_토큰이_바뀌고_로그아웃하면_세션이_사라진다() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/auth/login");
        String anonToken = sessions.csrfToken(r);
        String anonId = r.getSession().getId();

        String newToken = sessions.login(r, 42L);

        assertThat(r.getSession().getId()).isNotEqualTo(anonId);
        assertThat(newToken).isNotEqualTo(anonToken);
        assertThat(sessions.userId(r)).contains(42L);

        MockHttpSession session = (MockHttpSession) r.getSession();
        sessions.logout(r);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void 상수_시간_비교() {
        assertThat(CsrfFilter.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(CsrfFilter.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(CsrfFilter.constantTimeEquals("abc", "abcd")).isFalse();
    }
}
