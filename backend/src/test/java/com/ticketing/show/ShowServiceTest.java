package com.ticketing.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.common.ErrorCode;
import com.ticketing.show.ShowViews.ShowSummary;
import com.ticketing.support.TestApp;

class ShowServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private TestApp app;
    private long onSale;
    private long upcoming;
    private long soldOut;
    private long closed;

    @BeforeEach
    void setUp() {
        app = new TestApp(NOW, 10);
        onSale = app.store.addShow("재즈 % 나이트", Genre.CONCERT, NOW.minus(Duration.ofDays(1)));
        long p1 = app.store.addPerformance(onSale, NOW.plus(Duration.ofDays(5)));
        long p2 = app.store.addPerformance(onSale, NOW.plus(Duration.ofDays(2))); // 이 회차는 매진
        app.store.seatIds(p2).forEach(app.store::sell);
        app.store.sell(app.store.seatIds(p1).get(0));

        upcoming = app.store.addShow("뮤지컬 예정", Genre.MUSICAL, NOW.plus(Duration.ofDays(3)));
        app.store.addPerformance(upcoming, NOW.plus(Duration.ofDays(20)));

        soldOut = app.store.addShow("연극 매진", Genre.PLAY, NOW.minus(Duration.ofDays(5)));
        long p3 = app.store.addPerformance(soldOut, NOW.plus(Duration.ofDays(1)));
        app.store.seatIds(p3).forEach(app.store::sell);
        long p4 = app.store.addPerformance(soldOut, NOW.minus(Duration.ofDays(1))); // 종료 회차
        app.store.sell(app.store.seatIds(p4).get(0));

        closed = app.store.addShow("클래식 종료", Genre.CLASSIC, NOW.minus(Duration.ofDays(30)));
        app.store.addPerformance(closed, NOW.minus(Duration.ofDays(2)));
    }

    private ShowService.Query q(Genre genre, String text, SaleStatus status, ShowService.Sort sort) {
        return new ShowService.Query(genre, text, status, sort);
    }

    @Test
    void 공연_상태_규칙() {
        assertThat(SaleStatus.ofShow(List.of(SaleStatus.CLOSED, SaleStatus.CLOSED))).isEqualTo(SaleStatus.CLOSED);
        assertThat(SaleStatus.ofShow(List.of(SaleStatus.UPCOMING))).isEqualTo(SaleStatus.UPCOMING);
        assertThat(SaleStatus.ofShow(List.of(SaleStatus.CLOSED, SaleStatus.SOLD_OUT))).isEqualTo(SaleStatus.SOLD_OUT);
        assertThat(SaleStatus.ofShow(List.of(SaleStatus.SOLD_OUT, SaleStatus.ON_SALE))).isEqualTo(SaleStatus.ON_SALE);
        assertThat(SaleStatus.ofShow(List.of())).isEqualTo(SaleStatus.CLOSED);
        // 회차: 판매 시작 정각 = ON_SALE, 시작 정각 = CLOSED
        assertThat(SaleStatus.ofPerformance(NOW, NOW.plusSeconds(60), 1, NOW)).isEqualTo(SaleStatus.ON_SALE);
        assertThat(SaleStatus.ofPerformance(NOW.minusSeconds(60), NOW, 1, NOW)).isEqualTo(SaleStatus.CLOSED);
    }

    @Test
    void 목록_상태와_잔여석_랭킹() {
        List<ShowSummary> list = app.showService.list(q(null, null, null, null));
        ShowSummary jazz = list.stream().filter(s -> s.id() == onSale).findFirst().orElseThrow();

        assertThat(jazz.status()).isEqualTo(SaleStatus.ON_SALE);
        assertThat(jazz.totalSeats()).isEqualTo(16);
        assertThat(jazz.remainingSeats()).isEqualTo(7);
        assertThat(jazz.minPrice()).isEqualTo(50_000);
        assertThat(jazz.maxPrice()).isEqualTo(100_000);
        assertThat(jazz.firstStartsAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));
        assertThat(jazz.lastStartsAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));
        assertThat(jazz.rank()).isEqualTo(1); // 9석 판매
        assertThat(list).filteredOn(s -> s.id() == soldOut).singleElement()
                .satisfies(s -> {
                    assertThat(s.status()).isEqualTo(SaleStatus.SOLD_OUT);
                    assertThat(s.rank()).isEqualTo(2);
                    assertThat(s.remainingSeats()).isZero();
                });
        assertThat(list).filteredOn(s -> s.id() == upcoming).singleElement()
                .satisfies(s -> {
                    assertThat(s.status()).isEqualTo(SaleStatus.UPCOMING);
                    assertThat(s.rank()).isNull();
                });
        assertThat(list).filteredOn(s -> s.id() == closed).singleElement()
                .extracting(ShowSummary::status).isEqualTo(SaleStatus.CLOSED);
    }

    @Test
    void 정렬_날짜_랭킹_오픈예정() {
        assertThat(app.showService.list(q(null, null, null, ShowService.Sort.DATE)))
                .extracting(ShowSummary::id).containsExactly(closed, soldOut, onSale, upcoming);
        assertThat(app.showService.list(q(null, null, null, ShowService.Sort.RANKING)))
                .extracting(ShowSummary::id).startsWith(onSale, soldOut);
        assertThat(app.showService.list(q(null, null, null, ShowService.Sort.OPENING)))
                .extracting(ShowSummary::id).containsExactly(closed, soldOut, onSale, upcoming);
    }

    @Test
    void 장르_검색어_상태_필터와_LIKE_이스케이프() {
        assertThat(app.showService.list(q(Genre.PLAY, null, null, null))).extracting(ShowSummary::id)
                .containsExactly(soldOut);
        assertThat(app.showService.list(q(null, "%", null, null))).extracting(ShowSummary::id)
                .containsExactly(onSale); // %는 글자 그대로
        assertThat(app.showService.list(q(null, null, SaleStatus.UPCOMING, null))).extracting(ShowSummary::id)
                .containsExactly(upcoming);
        assertThat(ShowService.likePattern("a_b%c\\")).isEqualTo("%a\\_b\\%c\\\\%");
        assertThat(ShowService.likePattern("  ")).isNull();
    }

    @Test
    void 상세는_등급과_회차별_등급_잔여를_시작_시각_순으로() {
        ShowViews.ShowDetail d = app.showService.get(onSale);

        assertThat(d.maxSeatsPerBooking()).isEqualTo(4);
        assertThat(d.grades()).extracting(ShowViews.Grade::code).containsExactly("R", "S");
        assertThat(d.performances()).extracting(ShowViews.PerformanceSummary::status)
                .containsExactly(SaleStatus.SOLD_OUT, SaleStatus.ON_SALE);
        assertThat(d.performances().get(1).remainingByGrade()).containsExactly(
                new ShowViews.GradeRemaining("R", 3, 4), new ShowViews.GradeRemaining("S", 4, 4));
        assertThatThrownBy(() -> app.showService.get(424242)).extracting("code").isEqualTo(ErrorCode.SHOW_NOT_FOUND);
    }
}
