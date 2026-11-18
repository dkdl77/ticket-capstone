-- v3: 회원 계정 + 예매 소유자.
-- V1을 고치지 않고 위에 쌓는다 → 이미 v2로 운영 중인 DB도 볼륨을 다시 만들 필요 없이 그대로 올라간다.

CREATE TABLE users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    -- 앱이 trim + 소문자로 정규화해서 넣는다. utf8mb4_bin: 악센트·전각 문자를 같은 글자로 합치지 않도록(ai_ci면 é = e)
    email          VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    password_hash  VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- BCrypt. 원문 미저장
    name           VARCHAR(40)  NOT NULL,
    login_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,                          -- 시드 계정은 FALSE
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)  -- 가입 경쟁은 이 제약이 막는다 → 409 EMAIL_TAKEN
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 미리 판매된 시드 좌석의 소유 계정. 비밀번호 해시가 BCrypt 형식이 아니라('!') 어떤 비밀번호로도 로그인할 수 없고,
-- login_enabled = FALSE 로도 막는다. .invalid 도메인은 실제로 존재할 수 없는 예약 도메인이다(RFC 2606).
INSERT INTO users (email, password_hash, name, login_enabled)
VALUES ('seed-system@ticketing.invalid', '!', '시드 예매', FALSE);

-- 기존 예매(v2 시드·비회원 예매)는 시드 계정 소유로 옮긴 뒤 NOT NULL로 바꾼다.
ALTER TABLE reservations ADD COLUMN user_id BIGINT NULL AFTER performance_id;
UPDATE reservations SET user_id = (SELECT id FROM users WHERE email = 'seed-system@ticketing.invalid');
ALTER TABLE reservations
    MODIFY user_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_reservations_user FOREIGN KEY (user_id) REFERENCES users (id),
    ADD KEY idx_reservations_user_created (user_id, created_at),   -- 내 예매 목록(최근 순)
    ADD KEY idx_reservations_perf_user (performance_id, user_id);  -- 회차당 계정 4석 합산 검사
