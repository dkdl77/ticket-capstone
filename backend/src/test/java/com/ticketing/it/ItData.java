package com.ticketing.it;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 통합 테스트용 데이터. 테스트마다 새 공연·회차를 만들어 서로 간섭하지 않는다(ticketing_test DB, Redis DB 1). */
final class ItData {

    private ItData() {
    }

    static void flushRedis(StringRedisTemplate redis) {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    /** 공연 1개(등급 R 10만·S 5만, 2행×4석) + 회차 1개. 반환: performanceId */
    static long performance(JdbcClient jdbc, Instant saleOpensAt, Instant startsAt) {
        String slug = "it-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO shows (slug, title, genre, venue, sale_opens_at, age_rating, running_minutes,
                                           cast_members, description, notices, poster_seed, poster_hue,
                                           seat_rows, seats_per_row, aisle_after)
                        VALUES (:slug, '통합 테스트 공연', 'PLAY', '테스트홀', :opens, '전체 관람가', 90,
                                '["출연"]', '설명', '["유의"]', 1, 10, 2, 4, '[2]')
                        """)
                .param("slug", slug).param("opens", Timestamp.from(saleOpensAt)).update();
        long showId = jdbc.sql("SELECT id FROM shows WHERE slug = :s").param("s", slug).query(Long.class).single();
        jdbc.sql("INSERT INTO grades VALUES (:id, 'R', 'R석', 100000, 0), (:id, 'S', 'S석', 50000, 1)")
                .param("id", showId).update();
        jdbc.sql("INSERT INTO performances (show_id, seq, starts_at) VALUES (:id, 1, :at)")
                .param("id", showId).param("at", Timestamp.from(startsAt)).update();
        long perfId = jdbc.sql("SELECT id FROM performances WHERE show_id = :id").param("id", showId)
                .query(Long.class).single();
        for (String row : List.of("A", "B")) {
            for (int n = 1; n <= 4; n++) {
                jdbc.sql("INSERT INTO seats (performance_id, row_label, seat_number, grade_code) VALUES (:p, :r, :n, :g)")
                        .param("p", perfId).param("r", row).param("n", n).param("g", row.equals("A") ? "R" : "S")
                        .update();
            }
        }
        return perfId;
    }

    /** 새 계정(무작위 이메일). 반환: userId */
    static long user(com.ticketing.auth.AuthService auth) {
        return auth.signup("it-" + UUID.randomUUID() + "@example.com", "password-123", "통합테스트").id();
    }

    static List<Long> seatIds(JdbcClient jdbc, long perfId) {
        return jdbc.sql("SELECT id FROM seats WHERE performance_id = :p ORDER BY row_label, seat_number")
                .param("p", perfId).query(Long.class).list();
    }

    static int soldCount(JdbcClient jdbc, long perfId) {
        return jdbc.sql("SELECT COUNT(*) FROM seats WHERE performance_id = :p AND status = 'SOLD'")
                .param("p", perfId).query(Integer.class).single();
    }
}
