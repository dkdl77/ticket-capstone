-- v2 스키마: 공연(shows) → 회차(performances) → 좌석(seats), 예매(reservations) ↔ 예매 좌석(reservation_seats)
-- 아직 운영 데이터가 없어 v1의 V1을 다시 썼다. v1 스키마가 적용된 DB 볼륨은 새로 만들어야 한다(README 참고).

CREATE TABLE shows (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    slug             VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- 시드 식별용
    title            VARCHAR(100) NOT NULL,
    genre            VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue            VARCHAR(100) NOT NULL,
    sale_opens_at    DATETIME(3)  NOT NULL, -- UTC
    age_rating       VARCHAR(20)  NOT NULL,
    running_minutes  INT          NOT NULL,
    cast_members     JSON         NOT NULL, -- ["이름", ...]
    description      TEXT         NOT NULL, -- 일반 텍스트
    notices          JSON         NOT NULL, -- ["유의사항", ...]
    poster_seed      INT          NOT NULL,
    poster_hue       INT          NOT NULL,
    seat_rows        INT          NOT NULL, -- 좌석 배치: 행 수(A부터)
    seats_per_row    INT          NOT NULL, -- 좌석 배치: 행당 좌석 수
    aisle_after      JSON         NOT NULL, -- 이 번호 뒤에 통로 [4, 12]
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shows_slug (slug),
    KEY idx_shows_genre (genre),
    CONSTRAINT chk_shows_genre CHECK (genre IN ('CONCERT', 'MUSICAL', 'PLAY', 'CLASSIC')),
    CONSTRAINT chk_shows_hue CHECK (poster_hue BETWEEN 0 AND 359),
    CONSTRAINT chk_shows_layout CHECK (seat_rows BETWEEN 1 AND 26 AND seats_per_row BETWEEN 1 AND 60)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE grades (
    show_id     BIGINT      NOT NULL,
    code        VARCHAR(8)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- "VIP", "R", "S"
    name        VARCHAR(20) NOT NULL,                                       -- "R석"
    price       INT         NOT NULL,
    sort_order  INT         NOT NULL,                                       -- 높은 등급이 먼저
    PRIMARY KEY (show_id, code),
    CONSTRAINT fk_grades_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT chk_grades_price CHECK (price >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE performances (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    show_id    BIGINT      NOT NULL,
    seq        INT         NOT NULL, -- 공연 안에서의 회차 번호(시드 재실행 시 식별)
    starts_at  DATETIME(3) NOT NULL, -- UTC
    PRIMARY KEY (id),
    UNIQUE KEY uk_performances_show_seq (show_id, seq),
    KEY idx_performances_starts_at (starts_at),
    CONSTRAINT fk_performances_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            CHAR(12)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id  BIGINT       NOT NULL,
    total_price     INT          NOT NULL,
    name            VARCHAR(40)  NOT NULL,
    email_hash      CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- SHA-256(소문자 이메일). 원문 미저장
    email_masked    VARCHAR(260) NOT NULL,
    status          VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    cancelled_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservations_code (code),
    KEY idx_reservations_performance (performance_id),
    CONSTRAINT fk_reservations_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT chk_reservations_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 좌석의 현재 상태. SOLD일 때 reservation_id가 현재 소유 예매. 판매 처리는
-- UPDATE ... WHERE status = 'AVAILABLE' 의 영향 행 수로 검증한다(중복 판매의 최종 방어선).
CREATE TABLE seats (
    id              BIGINT     NOT NULL AUTO_INCREMENT,
    performance_id  BIGINT     NOT NULL,
    row_label       VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seat_number     INT        NOT NULL,
    grade_code      VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'AVAILABLE',
    reservation_id  BIGINT     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seats_position (performance_id, row_label, seat_number),
    KEY idx_seats_performance_status (performance_id, status),
    KEY idx_seats_reservation (reservation_id),
    CONSTRAINT fk_seats_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT fk_seats_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id),
    CONSTRAINT chk_seats_status CHECK (status IN ('AVAILABLE', 'SOLD')),
    CONSTRAINT chk_seats_sold_has_reservation CHECK ((status = 'SOLD') = (reservation_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 예매 당시의 좌석·가격 기록(취소 후에도 남는다).
CREATE TABLE reservation_seats (
    reservation_id  BIGINT NOT NULL,
    seat_id         BIGINT NOT NULL,
    price           INT    NOT NULL,
    PRIMARY KEY (reservation_id, seat_id),
    KEY idx_reservation_seats_seat (seat_id),
    CONSTRAINT fk_rs_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id),
    CONSTRAINT fk_rs_seat FOREIGN KEY (seat_id) REFERENCES seats (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
