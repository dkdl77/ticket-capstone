package com.ticketing.reservation;

import java.time.Instant;

/** 예매 저장 값. 이름·이메일(해시·마스킹)은 계정 정보에서 가져온 그 시점의 값이다. */
public record NewReservation(
        String code,
        long performanceId,
        long userId,
        int totalPrice,
        String name,
        String emailHash,
        String emailMasked,
        Instant createdAt) {
}
