package com.ticketing.show;

import java.time.Instant;

/** 회차 × 등급별 좌석 수와 판매 수(DB 기준). */
public record PerformanceGradeStat(long performanceId, long showId, Instant startsAt, String grade, int total, int sold) {
}
