package com.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ticketing.auth.AuthController;
import com.ticketing.auth.AuthService;
import com.ticketing.auth.SessionAuth;
import com.ticketing.common.ApiErrorWriter;
import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.config.AppProperties;
import com.ticketing.config.JacksonConfig;
import com.ticketing.queue.QueueController;
import com.ticketing.queue.QueueService;
import com.ticketing.queue.QueueState;
import com.ticketing.queue.QueueStore;
import com.ticketing.ratelimit.RateLimiter;
import com.ticketing.reservation.ReservationController;
import com.ticketing.reservation.ReservationService;
import com.ticketing.reservation.ReservationStatus;
import com.ticketing.reservation.ReservationView;
import com.ticketing.seat.SeatController;
import com.ticketing.seat.SeatService;
import com.ticketing.show.Genre;
import com.ticketing.show.SaleStatus;
import com.ticketing.show.ShowController;
import com.ticketing.show.ShowService;
import com.ticketing.show.ShowViews;

/**
 * HTTP 계층: 계약의 응답 형태, 입력 검증, 오류 형태(details 포함), 보안 헤더, 요청 제한 버킷,
 * v3 인증(세션의 uid)과 CSRF 필터. 서비스는 목. 세션은 MockHttpSession(실제 Redis 세션은 통합 테스트).
 */
@WebMvcTest(controllers = {ShowController.class, QueueController.class, SeatController.class,
        ReservationController.class, AuthController.class},
        properties = {
                "app.db.host=x", "app.db.port=3306", "app.db.name=x", "app.db.user=x", "app.db.password=x",
                "app.db.ssl-mode=PREFERRED", "app.db.pool-size=1",
                "app.redis.host=x", "app.redis.port=6379", "app.redis.database=0",
                "app.rate-limit.general-per-minute=100", "app.rate-limit.strict-per-minute=10",
                "app.rate-limit.poll-per-minute=200",
                "app.queue.max-active=100", "app.queue.admit-seconds=600", "app.queue.wait-ttl-seconds=30",
                "app.queue.hold-seconds=300", "app.auth.session-idle-minutes=30", "app.auth.session-cookie-secure=false",
                "spring.web.resources.add-mappings=false"
        })
@Import({JacksonConfig.class, ApiErrorWriter.class, SessionAuth.class, ApiWebTest.PropsConfig.class})
class ApiWebTest {

    @EnableConfigurationProperties(AppProperties.class)
    static class PropsConfig {
    }

    private static final String TOKEN = "A".repeat(43);
    private static final String KEY = "3f2c9d0e-8a7b-4c1d-9e2f-1a2b3c4d5e6f";
    private static final String HOLD = "h".repeat(22);
    private static final String CSRF = "c".repeat(43);
    private static final long USER = 7L;

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ShowService showService;
    @MockitoBean
    QueueService queueService;
    @MockitoBean
    SeatService seatService;
    @MockitoBean
    ReservationService reservationService;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    RateLimiter rateLimiter;

    @BeforeEach
    void allowAll() {
        when(rateLimiter.hit(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimiter.Decision(true, 9, 60));
    }

    /** 로그인한 세션(+CSRF 토큰). */
    private static MockHttpSession loggedIn() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(SessionAuth.USER_ID, USER);
        s.setAttribute(SessionAuth.CSRF, CSRF);
        return s;
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.session(loggedIn()).header("X-CSRF-Token", CSRF);
    }

    private static ReservationView sample() {
        return new ReservationView("ABCDEFGHJK23", new ReservationView.ShowRef(1, "재즈", "홀"),
                new ReservationView.PerformanceRef(5, Instant.parse("2026-12-01T10:30:00Z")),
                List.of(new ReservationView.SeatLine("A", 3, "R", 100_000)), 100_000, "홍길동", "ab***@example.com",
                ReservationStatus.CONFIRMED, Instant.parse("2026-11-13T03:00:00Z"), null);
    }

    // ---------------- 인증 ----------------

    @Test
    void auth_me는_항상_200이고_익명이면_user_null과_csrfToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").isEmpty())
                .andExpect(jsonPath("$.user").hasJsonPath())
                .andExpect(jsonPath("$.csrfToken").isString());

        when(authService.findUser(USER)).thenReturn(Optional.of(new AuthService.User(USER, "me@example.com", "나")));
        mvc.perform(get("/api/auth/me").session(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("me@example.com")) // 본인에게는 마스킹하지 않는다
                .andExpect(jsonPath("$.csrfToken").value(CSRF));
    }

    @Test
    void 로그인은_세션_ID를_바꾸고_새_csrfToken을_준다() throws Exception {
        MockHttpSession anon = new MockHttpSession();
        anon.setAttribute(SessionAuth.CSRF, CSRF);
        String before = anon.getId();
        when(authService.authenticate("me@example.com", "password-123"))
                .thenReturn(new AuthService.User(USER, "me@example.com", "나"));

        MvcResult r = mvc.perform(post("/api/auth/login").session(anon).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"me@example.com\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(USER))
                .andReturn();
        String newCsrf = com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.csrfToken");
        assertThat(newCsrf).isNotEqualTo(CSRF);
        assertThat(r.getRequest().getSession().getId()).isNotEqualTo(before);
    }

    @Test
    void 로그인_실패와_잠금은_계약_코드로() throws Exception {
        when(authService.authenticate(anyString(), eq("bad"))).thenThrow(new ApiException(ErrorCode.INVALID_CREDENTIALS));
        when(authService.authenticate(anyString(), eq("locked")))
                .thenThrow(new ApiException(ErrorCode.ACCOUNT_LOCKED).retryAfter(840));
        MockHttpSession anon = new MockHttpSession();
        anon.setAttribute(SessionAuth.CSRF, CSRF);

        mvc.perform(post("/api/auth/login").session(anon).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.co\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login").session(anon).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.co\",\"password\":\"locked\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void 가입은_201과_AuthState_입력_오류는_필드별_details() throws Exception {
        when(authService.signup("new@example.com", "password-123", "새사람"))
                .thenReturn(new AuthService.User(9, "new@example.com", "새사람"));
        MockHttpSession anon = new MockHttpSession();
        anon.setAttribute(SessionAuth.CSRF, CSRF);

        mvc.perform(post("/api/auth/signup").session(anon).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"password-123\",\"name\":\"새사람\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.id").value(9))
                .andExpect(jsonPath("$.csrfToken").isString());
        // 가입 성공으로 이 세션의 CSRF 토큰은 바뀌었다 → 옛 토큰은 403
        mvc.perform(post("/api/auth/signup").session(anon).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"password\":\"password-123\",\"name\":\"a\"}"))
                .andExpect(status().isForbidden());

        MockHttpSession fresh = new MockHttpSession();
        fresh.setAttribute(SessionAuth.CSRF, CSRF);
        mvc.perform(post("/api/auth/signup").session(fresh).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\",\"password\":\"short\",\"name\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("email"))
                .andExpect(jsonPath("$.error.details[1].field").value("password"));
    }

    @Test
    void CSRF_토큰이_없거나_다르면_403_CSRF_INVALID() throws Exception {
        mvc.perform(post("/api/queue/enter").session(loggedIn())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"performanceId\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/auth/logout").session(loggedIn()).header("X-CSRF-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden()); // 세션 없음 → 먼저 GET /api/auth/me
        verify(queueService, never()).enter(anyLong(), anyLong());
    }

    @Test
    void 로그인이_필요한_엔드포인트를_비로그인으로_부르면_401_UNAUTHENTICATED() throws Exception {
        MockHttpSession anon = new MockHttpSession();
        anon.setAttribute(SessionAuth.CSRF, CSRF);
        mvc.perform(post("/api/queue/enter").session(anon).header("X-CSRF-Token", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"performanceId\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/me/reservations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/performances/5/seats").header("X-Queue-Token", TOKEN))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 로그아웃은_204() throws Exception {
        mvc.perform(authed(post("/api/auth/logout"))).andExpect(status().isNoContent());
    }

    // ---------------- 조회 ----------------

    @Test
    void 공연_목록은_쿼리를_검증하고_정의되지_않은_파라미터는_400() throws Exception {
        when(showService.list(any())).thenReturn(List.of(new ShowViews.ShowSummary(1, "재즈", Genre.CONCERT, "홀",
                Instant.parse("2026-12-01T10:30:00Z"), Instant.parse("2026-12-02T10:30:00Z"),
                Instant.parse("2026-11-01T01:00:00Z"), 50_000, 100_000, SaleStatus.ON_SALE, 10, 20, null,
                new ShowViews.Poster(7, 210))));

        mvc.perform(get("/api/shows").param("genre", "concert").param("sort", "ranking").param("q", "재즈"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shows[0].genre").value("CONCERT"))
                .andExpect(jsonPath("$.shows[0].rank").hasJsonPath());
        mvc.perform(get("/api/shows").param("genre", "JAZZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("genre"));
        mvc.perform(get("/api/shows").param("q", "가".repeat(41))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/shows").param("page", "2")).andExpect(status().isBadRequest());
    }

    // ---------------- 대기열·좌석 ----------------

    @Test
    void 대기열_진입은_토큰을_주고_상태_조회는_토큰_필드가_없다() throws Exception {
        when(queueService.enter(USER, 5L)).thenReturn(new QueueState(TOKEN, QueueStore.State.WAITING, 3, null, 2));
        when(queueService.status(USER, TOKEN)).thenReturn(
                new QueueState(null, QueueStore.State.ADMITTED, 0, Instant.parse("2026-11-13T03:10:00Z"), 5));

        mvc.perform(authed(post("/api/queue/enter")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"performanceId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueToken").value(TOKEN))
                .andExpect(jsonPath("$.position").value(3));
        mvc.perform(get("/api/queue/status").session(loggedIn()).header("X-Queue-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueToken").doesNotExist());
        mvc.perform(authed(delete("/api/queue")).header("X-Queue-Token", TOKEN)).andExpect(status().isNoContent());
    }

    @Test
    void 좌석_토큰_오류는_401과_403() throws Exception {
        doThrow(new ApiException(ErrorCode.QUEUE_NOT_ADMITTED)).when(seatService).seatMap(eq(USER), eq(TOKEN), anyLong());
        mvc.perform(get("/api/performances/5/seats").session(loggedIn()).header("X-Queue-Token", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("QUEUE_NOT_ADMITTED"));
    }

    @Test
    void 선점_요청_검증과_SEAT_TAKEN_details() throws Exception {
        when(seatService.hold(eq(USER), eq(TOKEN), eq(5L), anyList())).thenThrow(SeatService.seatTaken(List.of(11L, 12L)));

        mvc.perform(authed(post("/api/performances/5/holds")).header("X-Queue-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[11,12]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.seatIds[1]").value(12));
        mvc.perform(authed(post("/api/performances/5/holds")).header("X-Queue-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1,2,3,4,5]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("seatIds"));
        mvc.perform(authed(delete("/api/holds/" + HOLD)).header("X-Queue-Token", TOKEN)).andExpect(status().isNoContent());
    }

    // ---------------- 예매·내 예매 ----------------

    @Test
    void 예매_확정은_holdId만_받고_201과_계약의_Reservation_형태() throws Exception {
        when(reservationService.confirm(USER, TOKEN, KEY, HOLD)).thenReturn(sample());

        mvc.perform(authed(post("/api/reservations")).header("X-Queue-Token", TOKEN).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + HOLD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.seats[0].row").value("A"))
                .andExpect(jsonPath("$.cancelledAt").hasJsonPath())
                .andExpect(header().string("Cache-Control", "no-store"));
        // v2처럼 name·email을 보내면 정의되지 않은 필드로 거부
        mvc.perform(authed(post("/api/reservations")).header("X-Queue-Token", TOKEN).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + HOLD + "\",\"name\":\"a\",\"email\":\"a@b.co\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 내_예매_목록과_취소() throws Exception {
        when(reservationService.myReservations(USER)).thenReturn(List.of(sample()));
        when(reservationService.cancel(USER, "ABCDEFGHJK23")).thenReturn(sample());
        when(reservationService.cancel(USER, "NOTMINE12345")).thenThrow(new ApiException(ErrorCode.RESERVATION_NOT_FOUND));

        mvc.perform(get("/api/me/reservations").session(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations[0].code").value("ABCDEFGHJK23"));
        mvc.perform(authed(post("/api/me/reservations/ABCDEFGHJK23/cancel"))).andExpect(status().isOk());
        mvc.perform(authed(post("/api/me/reservations/NOTMINE12345/cancel")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void v2의_비회원_조회_취소_경로는_없어졌다() throws Exception {
        mvc.perform(authed(post("/api/reservations/lookup")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCDEFGHJK23\",\"email\":\"a@b.co\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본문이_16KB를_넘으면_413_예상치_못한_오류는_500_고정문구() throws Exception {
        String big = "{\"holdId\":\"" + "가".repeat(6000) + "\"}";
        mvc.perform(authed(post("/api/reservations")).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
        when(reservationService.myReservations(USER)).thenThrow(new IllegalStateException("SELECT * FROM secret"));
        mvc.perform(get("/api/me/reservations").session(loggedIn()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    @Test
    void 요청_제한_버킷_분류와_429() throws Exception {
        assertThat(RateLimitFilter.bucketOf("GET", "/api/queue/status")).isEqualTo("poll");
        assertThat(RateLimitFilter.bucketOf("GET", "/api/performances/12/seats")).isEqualTo("poll");
        assertThat(RateLimitFilter.bucketOf("POST", "/api/performances/12/holds")).isEqualTo("strict");
        assertThat(RateLimitFilter.bucketOf("DELETE", "/api/holds/abc")).isEqualTo("strict");
        assertThat(RateLimitFilter.bucketOf("POST", "/api/queue/enter")).isEqualTo("strict");
        assertThat(RateLimitFilter.bucketOf("POST", "/api/auth/login")).isEqualTo("strict");
        assertThat(RateLimitFilter.bucketOf("POST", "/api/auth/signup")).isEqualTo("strict");
        assertThat(RateLimitFilter.bucketOf("POST", "/api/me/reservations/ABC/cancel")).isEqualTo("strict");
        assertThat(RateLimitFilter.bucketOf("GET", "/api/me/reservations")).isEqualTo("general");
        assertThat(RateLimitFilter.bucketOf("GET", "/api/auth/me")).isEqualTo("general");
        assertThat(RateLimitFilter.bucketOf("POST", "/api/auth/logout")).isEqualTo("general");

        when(rateLimiter.hit(eq("poll"), anyString(), eq(200), any(Duration.class)))
                .thenReturn(new RateLimiter.Decision(false, 0, 42));
        mvc.perform(get("/api/queue/status").session(loggedIn()).header("X-Queue-Token", TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"));
    }
}
