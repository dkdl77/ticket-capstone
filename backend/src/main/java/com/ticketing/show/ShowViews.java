package com.ticketing.show;

import java.time.Instant;
import java.util.List;

/** 계약 4.1의 공연 관련 응답 자료형. */
public final class ShowViews {

    private ShowViews() {
    }

    public record Grade(String code, String name, int price) {
    }

    /** 포스터는 이미지가 아니라 프론트가 그릴 때 쓰는 값. */
    public record Poster(int seed, int hue) {
    }

    public record ShowSummary(
            long id,
            String title,
            Genre genre,
            String venue,
            Instant firstStartsAt,
            Instant lastStartsAt,
            Instant saleOpensAt,
            int minPrice,
            int maxPrice,
            SaleStatus status,
            int remainingSeats,
            int totalSeats,
            Integer rank,
            Poster poster) {
    }

    public record GradeRemaining(String grade, int remaining, int total) {
    }

    public record PerformanceSummary(long id, Instant startsAt, SaleStatus status, List<GradeRemaining> remainingByGrade) {
    }

    /** ShowSummary의 모든 필드 + 상세 필드(계약: ShowSummary & {...}). */
    public record ShowDetail(
            long id,
            String title,
            Genre genre,
            String venue,
            Instant firstStartsAt,
            Instant lastStartsAt,
            Instant saleOpensAt,
            int minPrice,
            int maxPrice,
            SaleStatus status,
            int remainingSeats,
            int totalSeats,
            Integer rank,
            Poster poster,
            String ageRating,
            int runningMinutes,
            List<String> cast,
            String description,
            List<String> notices,
            int maxSeatsPerBooking,
            List<Grade> grades,
            List<PerformanceSummary> performances) {
    }

    public record ShowListResponse(List<ShowSummary> shows) {
    }
}
