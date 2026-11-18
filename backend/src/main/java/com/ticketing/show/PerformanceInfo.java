package com.ticketing.show;

import java.time.Instant;
import java.util.List;

/** 대기열·좌석·예매에서 쓰는 회차 정보(공연명·장소·좌석 배치 포함). */
public record PerformanceInfo(
        long id,
        long showId,
        String showTitle,
        String venue,
        Instant startsAt,
        Instant saleOpensAt,
        int seatRows,
        int seatsPerRow,
        List<Integer> aisleAfter) {

    /** 판매 상태(잔여 수량 제외 규칙만). 판매 기간이면 ON_SALE. */
    public SaleStatus timeStatus(Instant now) {
        return SaleStatus.ofPerformance(saleOpensAt, startsAt, 1, now);
    }
}
