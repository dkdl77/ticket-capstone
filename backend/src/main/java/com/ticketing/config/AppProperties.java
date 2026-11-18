package com.ticketing.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 모든 설정은 환경변수 → application.yml의 app.* 로 들어온다.
 * 시작 시 한 번 검증하고, 필수 값이 빠지면 컨텍스트 기동이 실패해 프로세스가 즉시 종료된다.
 * toString()은 비밀번호를 가린다(설정 덤프가 로그에 찍혀도 비밀값이 새지 않게).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Db db,
        @Valid @NotNull Redis redis,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Queue queue,
        @Valid @NotNull Auth auth) {

    /**
     * 세션(v3). sessionIdleMinutes: 요청이 없으면 이 시간 뒤 세션 만료.
     * sessionCookieSecure: 운영(https)은 true, 로컬 http는 false로 지정해야 브라우저가 쿠키를 보낸다.
     */
    public record Auth(
            @Min(value = 1, message = "SESSION_IDLE_MINUTES는 1 이상이어야 합니다.")
            @Max(value = 1440, message = "SESSION_IDLE_MINUTES는 1440 이하여야 합니다.") int sessionIdleMinutes,
            boolean sessionCookieSecure) {
    }

    public record Db(
            @NotBlank(message = "DB_HOST 환경변수가 필요합니다.") String host,
            @Min(1) @Max(65535) int port,
            @NotBlank(message = "DB_NAME 환경변수가 필요합니다.") String name,
            @NotBlank(message = "DB_USER 환경변수가 필요합니다.") String user,
            @NotBlank(message = "DB_PASSWORD 환경변수가 필요합니다.") String password,
            @NotBlank @Pattern(regexp = "DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY",
                    message = "DB_SSL_MODE는 DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY 중 하나여야 합니다.")
            String sslMode,
            @Min(1) @Max(100) int poolSize) {

        @Override
        public String toString() {
            return "Db[host=" + host + ", port=" + port + ", name=" + name + ", user=" + user
                    + ", password=****, sslMode=" + sslMode + ", poolSize=" + poolSize + "]";
        }
    }

    public record Redis(
            @NotBlank(message = "REDIS_HOST 환경변수가 필요합니다.") String host,
            @Min(1) @Max(65535) int port,
            String password,
            boolean ssl,
            @Min(0) @Max(15) int database) {

        @Override
        public String toString() {
            return "Redis[host=" + host + ", port=" + port + ", password=****, ssl=" + ssl
                    + ", database=" + database + "]";
        }
    }

    /** IP당 분당 요청 수. general = 일반 조회, strict = 진입·선점·예매·조회·취소, poll = 대기 상태·좌석 조회 폴링. */
    public record RateLimit(
            @Min(1) int generalPerMinute,
            @Min(1) int strictPerMinute,
            @Min(1) int pollPerMinute) {
    }

    public record Cors(
            List<@Pattern(regexp = "^https?://[^/\\s*]+$",
                    message = "CORS_ALLOWED_ORIGINS는 쉼표로 구분한 http(s)://호스트[:포트] 목록이어야 합니다.")
                    String> allowedOrigins) {

        public List<String> origins() {
            return allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().filter(s -> s != null && !s.isBlank()).toList();
        }
    }

    /**
     * 대기열·선점 (ARCHITECTURE.md 3.2).
     * maxActive: 회차당 좌석 화면 동시 입장 인원, admitSeconds: 입장 권한 유효 시간,
     * waitTtlSeconds: 대기 중 폴링이 이만큼 끊기면 대기열에서 제외, holdSeconds: 좌석 임시 선점 시간.
     */
    public record Queue(
            @Min(value = 1, message = "QUEUE_MAX_ACTIVE는 1 이상이어야 합니다.") int maxActive,
            @Min(value = 30, message = "QUEUE_ADMIT_SECONDS는 30 이상이어야 합니다.") int admitSeconds,
            @Min(value = 3, message = "QUEUE_WAIT_TTL_SECONDS는 3 이상이어야 합니다.") int waitTtlSeconds,
            @Min(value = 10, message = "HOLD_SECONDS는 10 이상이어야 합니다.") int holdSeconds) {

        public Duration admit() {
            return Duration.ofSeconds(admitSeconds);
        }

        public Duration waitTtl() {
            return Duration.ofSeconds(waitTtlSeconds);
        }

        public Duration hold() {
            return Duration.ofSeconds(holdSeconds);
        }
    }
}
