package com.ticketing.reservation;

import java.time.Instant;
import java.util.List;

/** 계약 4.1의 Reservation 응답. 이메일은 마스킹된 값만 나간다. */
public record ReservationView(
        String code,
        ShowRef show,
        PerformanceRef performance,
        List<SeatLine> seats,
        int totalPrice,
        String name,
        String emailMasked,
        ReservationStatus status,
        Instant createdAt,
        Instant cancelledAt) {

    public record ShowRef(long id, String title, String venue) {
    }

    public record PerformanceRef(long id, Instant startsAt) {
    }

    public record SeatLine(String row, int number, String grade, int price) {
    }

    public static ReservationView of(ReservationRow r, List<SeatLine> seats) {
        return new ReservationView(r.code(), new ShowRef(r.showId(), r.showTitle(), r.venue()),
                new PerformanceRef(r.performanceId(), r.performanceStartsAt()), seats, r.totalPrice(), r.name(),
                r.emailMasked(), r.status(), r.createdAt(), r.cancelledAt());
    }
}
