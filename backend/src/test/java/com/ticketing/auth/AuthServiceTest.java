package com.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.support.TestApp;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private TestApp app;

    @BeforeEach
    void setUp() {
        app = new TestApp(NOW, 10);
    }

    @Test
    void 가입하면_이메일은_소문자로_정규화되고_비밀번호는_BCrypt_해시로만_저장된다() {
        AuthService.User u = app.auth.signup("  Kim.Ticket@Example.COM ", "correct horse", " 김티켓 ");

        assertThat(u.email()).isEqualTo("kim.ticket@example.com");
        assertThat(u.name()).isEqualTo("김티켓");
        String hash = app.users.rows.get(u.id()).passwordHash();
        assertThat(hash).startsWith("$2").doesNotContain("correct horse");
    }

    @Test
    void 같은_이메일은_대소문자가_달라도_EMAIL_TAKEN() {
        app.auth.signup("dup@example.com", "password-1", "a");
        assertThatThrownBy(() -> app.auth.signup("DUP@example.com", "password-2", "b"))
                .extracting("code").isEqualTo(ErrorCode.EMAIL_TAKEN);
    }

    @Test
    void 비밀번호는_72바이트를_넘으면_거부한다_BCrypt가_뒤를_잘라내기_때문() {
        assertThatThrownBy(() -> app.auth.signup("k@example.com", "가".repeat(25), "a")) // 75바이트
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(app.auth.signup("k@example.com", "가".repeat(24), "a").id()).isPositive(); // 72바이트
    }

    @Test
    void 로그인_성공_이메일_대소문자_무시() {
        long id = app.auth.signup("me@example.com", "password-123", "나").id();
        assertThat(app.auth.authenticate(" ME@Example.com", "password-123").id()).isEqualTo(id);
    }

    @Test
    void 없는_이메일과_틀린_비밀번호는_같은_INVALID_CREDENTIALS() {
        app.auth.signup("me@example.com", "password-123", "나");

        ApiException wrongPw = catchApi(() -> app.auth.authenticate("me@example.com", "wrong-password"));
        ApiException noUser = catchApi(() -> app.auth.authenticate("nobody@example.com", "password-123"));

        assertThat(wrongPw.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(noUser.code()).isEqualTo(wrongPw.code());
        assertThat(noUser.getMessage()).isEqualTo(wrongPw.getMessage());
    }

    @Test
    void 로그인_불가_계정_시드계정은_올바른_비밀번호_형식이어도_로그인할_수_없다() {
        app.users.addDisabled("seed-system@ticketing.invalid");
        assertThatThrownBy(() -> app.auth.authenticate("seed-system@ticketing.invalid", "!"))
                .extracting("code").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 연속_5회_실패하면_15분_잠기고_그동안은_맞는_비밀번호도_거부_성공하면_초기화() {
        app.auth.signup("me@example.com", "password-123", "나");
        for (int i = 0; i < 4; i++) {
            assertThat(catchApi(() -> app.auth.authenticate("me@example.com", "bad")).code())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
        ApiException fifth = catchApi(() -> app.auth.authenticate("me@example.com", "bad"));
        assertThat(fifth.code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED);
        assertThat(fifth.retryAfterSeconds()).isEqualTo(900L);

        assertThat(catchApi(() -> app.auth.authenticate("me@example.com", "password-123")).code())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        app.store.clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(app.auth.authenticate("me@example.com", "password-123").email()).isEqualTo("me@example.com");
        // 성공으로 초기화 → 다시 4번 틀려도 잠기지 않는다
        for (int i = 0; i < 4; i++) {
            assertThat(catchApi(() -> app.auth.authenticate("me@example.com", "bad")).code())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    @Test
    void 없는_계정에도_같은_잠금이_적용되어_잠금_여부로_가입_여부를_알_수_없다() {
        for (int i = 0; i < 4; i++) {
            catchApi(() -> app.auth.authenticate("ghost@example.com", "x"));
        }
        assertThat(catchApi(() -> app.auth.authenticate("ghost@example.com", "x")).code())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void 시연_계정은_다시_실행하면_비밀번호가_갱신된다() {
        app.auth.upsertDemoAccount("demo@example.com", "first-pass", "시연");
        app.auth.upsertDemoAccount("demo@example.com", "second-pass", "시연");
        assertThatThrownBy(() -> app.auth.authenticate("demo@example.com", "first-pass"))
                .extracting("code").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(app.auth.authenticate("demo@example.com", "second-pass").name()).isEqualTo("시연");
        assertThatThrownBy(() -> app.auth.upsertDemoAccount("demo@example.com", "short", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ApiException catchApi(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("ApiException이 나야 합니다");
    }
}
