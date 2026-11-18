package com.ticketing.reservation;

import java.time.Instant;

/** DB의 예매 한 건 + 응답에 필요한 공연·회차 요약. 이메일은 원문을 저장하지 않는다(해시 + 마스킹만). */
public record ReservationRow(
        long id,
        String code,
        long performanceId,
        Instant performanceStartsAt,
        long showId,
        String showTitle,
        String venue,
        int totalPrice,
        String name,
        String emailHash,
        String emailMasked,
        ReservationStatus status,
        Instant createdAt,
        Instant cancelledAt) {

    public ReservationRow cancelled(Instant at) {
        return new ReservationRow(id, code, performanceId, performanceStartsAt, showId, showTitle, venue, totalPrice,
                name, emailHash, emailMasked, ReservationStatus.CANCELLED, createdAt, at);
    }
}
