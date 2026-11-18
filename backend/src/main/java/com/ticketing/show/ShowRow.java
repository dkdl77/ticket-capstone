package com.ticketing.show;

import java.time.Instant;
import java.util.List;

/** DB의 공연 한 건(좌석 배치 정보 포함). */
public record ShowRow(
        long id,
        String title,
        Genre genre,
        String venue,
        Instant saleOpensAt,
        String ageRating,
        int runningMinutes,
        List<String> cast,
        String description,
        List<String> notices,
        int posterSeed,
        int posterHue,
        int seatRows,
        int seatsPerRow,
        List<Integer> aisleAfter) {
}
