package com.ticketing.show;

import java.time.Instant;
import java.util.Collection;

/** 판매 상태와 계약 4.1의 상태 규칙. */
public enum SaleStatus {
    UPCOMING, ON_SALE, SOLD_OUT, CLOSED;

    /** 회차: 현재 < 공연 saleOpensAt → UPCOMING, 현재 ≥ 회차 startsAt → CLOSED, 잔여 0 → SOLD_OUT, 그 외 ON_SALE. */
    public static SaleStatus ofPerformance(Instant saleOpensAt, Instant startsAt, int remaining, Instant now) {
        if (now.isBefore(saleOpensAt)) {
            return UPCOMING;
        }
        if (!now.isBefore(startsAt)) {
            return CLOSED;
        }
        if (remaining <= 0) {
            return SOLD_OUT;
        }
        return ON_SALE;
    }

    /** 공연: 모든 회차 CLOSED → CLOSED, UPCOMING이면 UPCOMING, 닫히지 않은 회차가 전부 SOLD_OUT → SOLD_OUT, 그 외 ON_SALE. */
    public static SaleStatus ofShow(Collection<SaleStatus> performanceStatuses) {
        if (performanceStatuses.isEmpty() || performanceStatuses.stream().allMatch(s -> s == CLOSED)) {
            return CLOSED;
        }
        if (performanceStatuses.contains(UPCOMING)) {
            return UPCOMING;
        }
        boolean allOpenSoldOut = performanceStatuses.stream().filter(s -> s != CLOSED).allMatch(s -> s == SOLD_OUT);
        return allOpenSoldOut ? SOLD_OUT : ON_SALE;
    }
}
