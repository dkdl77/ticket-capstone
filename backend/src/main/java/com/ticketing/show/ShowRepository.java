package com.ticketing.show;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShowRepository {

    /**
     * 공연 목록. genre·titleOrVenueLike가 null이면 그 조건을 쓰지 않는다.
     * likePattern은 호출자가 %·_·\ 를 이스케이프한 값(예: "%재즈%").
     */
    List<ShowRow> findAll(Genre genre, String likePattern);

    Optional<ShowRow> findById(long id);

    List<GradeRow> findGrades(Collection<Long> showIds);

    /** 회차 × 등급별 좌석·판매 수. showIds가 null이면 모든 공연. */
    List<PerformanceGradeStat> findPerformanceStats(Collection<Long> showIds);

    Optional<PerformanceInfo> findPerformance(long performanceId);
}
