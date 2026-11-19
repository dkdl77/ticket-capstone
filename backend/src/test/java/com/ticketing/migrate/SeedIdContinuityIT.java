package com.ticketing.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.TicketingApplication;
import com.ticketing.common.Transactions;

/**
 * 실제 MySQL: migrate를 여러 번 돌려도 공연 id(AUTO_INCREMENT)가 소모되지 않고,
 * 예전 방식(INSERT ... ON DUPLICATE KEY UPDATE)으로 카운터가 올라가 있던 DB도 시드 시작 시 되돌려져
 * 새 공연이 "기존 최대 id + 1"부터 연속으로 매겨진다.
 * migrate 프로필로 띄우므로 컨텍스트가 뜰 때 SeedRunner가 시드를 한 번 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles(TicketingApplication.MIGRATE_PROFILE)
class SeedIdContinuityIT {

    @Autowired
    Seeder seeder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Transactions tx;

    private List<Long> seedShowIds() {
        return Seeder.SHOWS.stream()
                .map(s -> jdbc.sql("SELECT id FROM shows WHERE slug = :s").param("s", s.slug())
                        .query(Long.class).single())
                .toList();
    }

    private long maxId() {
        return jdbc.sql("SELECT MAX(id) FROM shows").query(Long.class).single();
    }

    /** 실제 AUTO_INCREMENT 값(통계 캐시를 끄고 같은 커넥션에서 읽는다). */
    private long autoIncrement() {
        return tx.inTransaction(() -> {
            jdbc.sql("SET SESSION information_schema_stats_expiry = 0").update();
            return jdbc.sql("""
                            SELECT AUTO_INCREMENT FROM information_schema.TABLES
                             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shows'
                            """).query(Long.class).single();
        });
    }

    private long insertProbeShow() {
        String slug = "it-probe-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO shows (slug, title, genre, venue, sale_opens_at, age_rating, running_minutes,
                                           cast_members, description, notices, poster_seed, poster_hue,
                                           seat_rows, seats_per_row, aisle_after)
                        VALUES (:slug, '탐침', 'PLAY', '테스트홀', NOW(3), '전체 관람가', 60,
                                '[]', '', '[]', 1, 1, 8, 12, '[6]')
                        """).param("slug", slug).update();
        return jdbc.sql("SELECT id FROM shows WHERE slug = :s").param("s", slug).query(Long.class).single();
    }

    @Test
    void 시드_공연_18개는_SHOWS_순서대로_id가_증가하고_다시_돌려도_id와_카운터가_바뀌지_않는다() {
        List<Long> ids = seedShowIds();
        assertThat(ids).hasSize(18).isSorted().doesNotHaveDuplicates();
        long before = autoIncrement();

        for (int i = 0; i < 3; i++) {
            seeder.seed(Instant.now());
        }

        assertThat(seedShowIds()).isEqualTo(ids);
        assertThat(autoIncrement()).isEqualTo(before); // 예전 방식이면 회당 18씩 올라갔다
    }

    @Test
    void 카운터가_부풀려진_기존_DB도_시드를_돌리면_다음_공연이_최대_id_다음_번호가_된다() {
        long max = maxId();
        // 예전 ON DUPLICATE KEY UPDATE 방식으로 migrate를 여러 번 돌린 DB처럼 카운터를 올려 둔다
        jdbc.sql("ALTER TABLE shows AUTO_INCREMENT = " + (max + 40)).update();
        assertThat(autoIncrement()).isEqualTo(max + 40);

        seeder.seed(Instant.now());

        assertThat(autoIncrement()).isEqualTo(max + 1);
        assertThat(insertProbeShow()).isEqualTo(max + 1);
    }
}
