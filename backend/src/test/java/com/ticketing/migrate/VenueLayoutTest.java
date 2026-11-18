package com.ticketing.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class VenueLayoutTest {

    @Test
    void 모든_템플릿은_계약_4_4의_8에서12행_12에서18석_범위() {
        for (VenueLayout l : VenueLayout.values()) {
            assertThat(l.rows()).as(l.name()).isBetween(8, 12);
            assertThat(l.seatsPerRow()).as(l.name()).isBetween(12, 18);
            assertThat(l.aisleAfter()).allMatch(a -> a >= 1 && a < l.seatsPerRow());
            // 가장 긴 행이 seatsPerRow를 꽉 채운다(seatsPerRow = 가장 긴 행 기준)
            int longest = 0;
            for (int r = 0; r < l.rows(); r++) {
                int w = 0;
                for (int c = 1; c <= l.seatsPerRow(); c++) {
                    w += l.exists(r, c) ? 1 : 0;
                }
                longest = Math.max(longest, w);
            }
            assertThat(longest).as(l.name()).isEqualTo(l.seatsPerRow());
        }
    }

    @Test
    void 템플릿마다_좌석_수와_통로가_다르다() {
        assertThat(VenueLayout.SMALL.seatCount()).isEqualTo(96);
        assertThat(VenueLayout.MEDIUM.seatCount()).isEqualTo(216);
        assertThat(VenueLayout.FAN.seatCount()).isEqualTo(2 * (8 + 10 + 12 + 14 + 16));
        assertThat(VenueLayout.CUT_CORNERS.seatCount()).isEqualTo(11 * 16 - 2 * 4 - 2);
        Set<Object> aisles = new HashSet<>();
        for (VenueLayout l : VenueLayout.values()) {
            aisles.add(l.aisleAfter());
        }
        assertThat(aisles).hasSize(VenueLayout.values().length);
    }

    @Test
    void 부채꼴은_앞줄이_가운데_정렬로_짧고_가장자리_빠짐은_앞줄_양끝이_비어_있다() {
        // FAN A행: 16칸 중 가운데 8칸(5~12)만 좌석
        assertThat(VenueLayout.FAN.exists(0, 4)).isFalse();
        assertThat(VenueLayout.FAN.exists(0, 5)).isTrue();
        assertThat(VenueLayout.FAN.exists(0, 12)).isTrue();
        assertThat(VenueLayout.FAN.exists(0, 13)).isFalse();
        assertThat(VenueLayout.FAN.exists(9, 1)).isTrue();
        // CUT_CORNERS: A행 1·2·15·16번 없음, 마지막 행 1·16번 없음
        assertThat(VenueLayout.CUT_CORNERS.exists(0, 2)).isFalse();
        assertThat(VenueLayout.CUT_CORNERS.exists(0, 3)).isTrue();
        assertThat(VenueLayout.CUT_CORNERS.exists(1, 15)).isFalse();
        assertThat(VenueLayout.CUT_CORNERS.exists(10, 1)).isFalse();
        assertThat(VenueLayout.CUT_CORNERS.exists(10, 2)).isTrue();
        assertThat(VenueLayout.CUT_CORNERS.exists(5, 1)).isTrue();
    }

    @Test
    void 시드의_같은_공연장_이름은_같은_템플릿을_쓴다() {
        java.util.Map<String, VenueLayout> byVenue = new java.util.HashMap<>();
        for (Seeder.SeedShow s : Seeder.SHOWS) {
            VenueLayout prev = byVenue.putIfAbsent(s.venue(), s.layout());
            assertThat(prev == null || prev == s.layout()).as(s.venue()).isTrue();
        }
        assertThat(Seeder.SHOWS).extracting(Seeder.SeedShow::layout).contains(VenueLayout.values());
    }
}
