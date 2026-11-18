package com.ticketing.seat;

import java.time.Instant;
import java.util.List;

import com.ticketing.show.ShowViews.Grade;

/** 계약 4.1의 좌석 관련 응답 자료형. */
public final class SeatViews {

    private SeatViews() {
    }

    public enum SeatStatus { AVAILABLE, HELD, SOLD, MINE }

    public record Seat(long id, String row, int number, String grade, SeatStatus status) {
    }

    public record PerformanceRef(long id, long showId, String showTitle, String venue, Instant startsAt) {
    }

    public record HoldView(String holdId, List<Long> seatIds, int totalPrice, Instant expiresAt) {

        public static HoldView of(HoldStore.Hold h) {
            return new HoldView(h.holdId(), h.seatIds(), h.totalPrice(), h.expiresAt());
        }
    }

    public record SeatMap(
            PerformanceRef performance,
            List<Grade> grades,
            List<String> rows,
            int seatsPerRow,
            List<Integer> aisleAfter,
            List<Seat> seats,
            HoldView myHold) {
    }
}
