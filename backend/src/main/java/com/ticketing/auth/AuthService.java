package com.ticketing.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.ticketing.common.ApiError;
import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.reservation.EmailPrivacy;

/**
 * 가입·로그인 규칙(ARCHITECTURE.md 3.2.3, 3.4). 세션 처리는 컨트롤러 쪽 {@link SessionAuth}가 한다.
 * - 비밀번호: BCrypt(cost 12). 원문·해시를 로그와 응답에 남기지 않는다.
 * - 로그인 실패: 이메일 없음/비밀번호 틀림을 구분하지 않고(INVALID_CREDENTIALS), 없는 계정에도 더미 해시를 비교해 응답 시간을 맞춘다.
 * - 계정당 연속 5회 실패 → 15분 잠금(429 ACCOUNT_LOCKED + Retry-After). 성공하면 초기화.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    /** BCrypt는 72바이트 뒤를 버린다 → 그보다 긴 비밀번호는 받지 않는다(한글은 1자 3바이트). */
    public static final int MAX_PASSWORD_BYTES = 72;

    public record User(long id, String email, String name) {
    }

    private final UserRepository users;
    private final LoginAttemptStore attempts;
    private final PasswordEncoder encoder;
    private volatile String dummyHash;

    public AuthService(UserRepository users, LoginAttemptStore attempts, PasswordEncoder encoder) {
        this.users = users;
        this.attempts = attempts;
        this.encoder = encoder;
    }

    /**
     * 같은 cost의 더미 해시. 없는 계정 로그인에서 이 해시와 비교해 응답 시간 차이를 없앤다.
     * 기동 시간을 늘리지 않도록 처음 필요할 때 한 번 만든다.
     */
    private String dummyHash() {
        String h = dummyHash;
        if (h == null) {
            synchronized (this) {
                if (dummyHash == null) {
                    dummyHash = encoder.encode(UUID.randomUUID().toString());
                }
                h = dummyHash;
            }
        }
        return h;
    }

    public User signup(String email, String password, String name) {
        String normalized = EmailPrivacy.normalize(email);
        checkPasswordBytes(password);
        String trimmedName = name.trim();
        try {
            long id = users.insert(normalized, encoder.encode(password), trimmedName);
            return new User(id, normalized, trimmedName);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
    }

    public User authenticate(String email, String password) {
        String normalized = EmailPrivacy.normalize(email);
        String accountKey = EmailPrivacy.hash(normalized);
        Optional<Duration> locked = attempts.lockedFor(accountKey, MAX_FAILURES);
        if (locked.isPresent()) {
            throw lockedError(locked.get());
        }
        Optional<UserRepository.UserRow> row = users.findByEmail(normalized);
        boolean ok;
        if (row.isPresent() && row.get().loginEnabled() && looksLikeBcrypt(row.get().passwordHash())) {
            ok = encoder.matches(password, row.get().passwordHash());
        } else {
            encoder.matches(password, dummyHash()); // 없는 계정·로그인 불가 계정도 같은 시간이 걸리게
            ok = false;
        }
        if (!ok) {
            Optional<Duration> nowLocked = attempts.recordFailure(accountKey, MAX_FAILURES, LOCK_DURATION);
            if (nowLocked.isPresent()) {
                log.warn("로그인 연속 실패로 계정 잠금(15분)");
                throw lockedError(nowLocked.get());
            }
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        attempts.reset(accountKey);
        UserRepository.UserRow u = row.get();
        return new User(u.id(), u.email(), u.name());
    }

    public Optional<User> findUser(long id) {
        return users.findById(id).filter(UserRepository.UserRow::loginEnabled)
                .map(u -> new User(u.id(), u.email(), u.name()));
    }

    /** 시드 전용(시연 계정). */
    public void upsertDemoAccount(String email, String password, String name) {
        checkPasswordBytes(password);
        if (password.length() < 8) {
            throw new IllegalArgumentException("SEED_DEMO_PASSWORD는 8자 이상이어야 합니다.");
        }
        users.upsertDemo(EmailPrivacy.normalize(email), encoder.encode(password), name.trim());
    }

    private static void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            String message = "비밀번호가 너무 깁니다. 영문 기준 72자, 한글 기준 24자 이내로 입력해 주세요.";
            throw new ApiException(ErrorCode.VALIDATION_ERROR, message,
                    java.util.List.of(new ApiError.FieldViolation("password", message)));
        }
    }

    private static boolean looksLikeBcrypt(String hash) {
        return hash != null && hash.startsWith("$2");
    }

    private static ApiException lockedError(Duration remaining) {
        long minutes = Math.max(1, (remaining.toSeconds() + 59) / 60);
        return new ApiException(ErrorCode.ACCOUNT_LOCKED,
                "로그인에 여러 번 실패해 잠시 잠겼습니다. " + minutes + "분 뒤 다시 시도해 주세요.")
                .retryAfter(remaining.toSeconds());
    }
}
