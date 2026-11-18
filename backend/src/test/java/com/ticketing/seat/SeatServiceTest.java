package com.ticketing.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.seat.SeatViews.SeatStatus;
import com.ticketing.show.Genre;
import com.ticketing.support.TestApp;

class SeatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private TestApp app;
    private long perf;
    private List<Long> seats; // A1..A4 (R석 10만), B1..B4 (S석 5만)
    private String me;
    private String other;
    private long meId;
    private long otherId;

    @BeforeEach
    void setUp() {
        app = new TestApp(NOW, 10);
        long show = app.store.addShow("판매 중", Genre.CONCERT, NOW.minus(Duration.ofDays(1)));
        perf = app.store.addPerformance(show, NOW.plus(Duration.ofDays(3)));
        seats = app.store.seatIds(perf);
        meId = app.user("me@x.com");
        otherId = app.user("other@x.com");
        me = app.queue.enter(meId, perf).queueToken();
        other = app.queue.enter(otherId, perf).queueToken();
    }

    @Test
    void 선점하면_가격_합계와_만료시각을_돌려주고_좌석화면에서_내것은_MINE_남의것은_HELD() {
        SeatViews.HoldView mine = app.seatService.hold(meId, me, perf, List.of(seats.get(4), seats.get(0)));
        app.seatService.hold(otherId, other, perf, List.of(seats.get(1)));

        assertThat(mine.holdId()).hasSize(22);
        assertThat(mine.seatIds()).containsExactly(seats.get(0), seats.get(4)); // 좌석 순서로 정렬
        assertThat(mine.totalPrice()).isEqualTo(150_000);
        assertThat(mine.expiresAt()).isEqualTo(NOW.plusSeconds(300));

        SeatViews.SeatMap map = app.seatService.seatMap(meId, me, perf);
        assertThat(map.rows()).containsExactly("A", "B");
        assertThat(map.seats()).extracting(SeatViews.Seat::status).containsExactly(
                SeatStatus.MINE, SeatStatus.HELD, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE,
                SeatStatus.MINE, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE);
        assertThat(map.myHold().holdId()).isEqualTo(mine.holdId());
        // 상대방 화면에서는 내 선점이 HELD로 보인다
        assertThat(app.seatService.seatMap(otherId, other, perf).seats().get(0).status()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void 선점은_전부_아니면_전무_하나라도_잡혀_있으면_아무것도_잡지_않는다() {
        app.seatService.hold(otherId, other, perf, List.of(seats.get(1)));

        assertThatThrownBy(() -> app.seatService.hold(meId, me, perf, List.of(seats.get(0), seats.get(1), seats.get(2))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SEAT_TAKEN);
                    assertThat(((SeatService.SeatTaken) e.details()).seatIds()).containsExactly(seats.get(1));
                });
        assertThat(app.holds.holdersOf(perf, seats)).containsOnlyKeys(seats.get(1));
    }

    @Test
    void 판매된_좌석은_SEAT_TAKEN() {
        app.store.sell(seats.get(3));
        assertThatThrownBy(() -> app.seatService.hold(meId, me, perf, List.of(seats.get(3))))
                .extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(app.seatService.seatMap(meId, me, perf).seats().get(3).status()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void 다시_선점하면_이전_선점이_해제되고_겹치는_내_좌석은_다시_잡을_수_있다() {
        SeatViews.HoldView first = app.seatService.hold(meId, me, perf, List.of(seats.get(0), seats.get(1)));
        SeatViews.HoldView second = app.seatService.hold(meId, me, perf, List.of(seats.get(1), seats.get(2)));

        assertThat(second.holdId()).isNotEqualTo(first.holdId());
        assertThat(app.holds.find(first.holdId())).isEmpty();
        assertThat(app.holds.holdersOf(perf, seats)).containsOnlyKeys(seats.get(1), seats.get(2));
    }

    @Test
    void 중복_좌석_5석_이상_없는_좌석은_400() {
        assertThatThrownBy(() -> app.seatService.hold(meId, me, perf, List.of(seats.get(0), seats.get(0))))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> app.seatService.hold(meId, me, perf, seats.subList(0, 5)))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> app.seatService.hold(meId, me, perf, List.of(987_654L)))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void 선점은_입장_만료를_넘지_못하고_TTL이_지나면_자동_해제된다() {
        app.store.clock.advance(Duration.ofSeconds(500)); // 입장 만료까지 100초
        SeatViews.HoldView h = app.seatService.hold(meId, me, perf, List.of(seats.get(0)));
        assertThat(h.expiresAt()).isEqualTo(NOW.plusSeconds(600));

        app.store.clock.advance(Duration.ofSeconds(101));
        assertThat(app.holds.holdersOf(perf, seats)).isEmpty();
    }

    @Test
    void 남의_선점은_해제할_수_없고_404() {
        SeatViews.HoldView mine = app.seatService.hold(meId, me, perf, List.of(seats.get(0)));

        assertThatThrownBy(() -> app.seatService.release(otherId, other, mine.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        app.seatService.release(meId, me, mine.holdId());
        assertThat(app.holds.holdersOf(perf, seats)).isEmpty();
    }

    @Test
    void 대기열_이탈하면_선점도_해제된다() {
        app.seatService.hold(meId, me, perf, List.of(seats.get(0)));
        app.queue.leave(meId, me);
        assertThat(app.holds.holdersOf(perf, seats)).isEmpty();
        assertThatThrownBy(() -> app.seatService.seatMap(meId, me, perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }

    @Test
    void 다른_계정은_내_토큰으로_좌석을_보거나_잡거나_내_선점을_풀_수_없다() {
        SeatViews.HoldView mine = app.seatService.hold(meId, me, perf, List.of(seats.get(0)));

        assertThatThrownBy(() -> app.seatService.seatMap(otherId, me, perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> app.seatService.hold(otherId, me, perf, List.of(seats.get(1))))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> app.seatService.release(otherId, me, mine.holdId()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThat(app.holds.find(mine.holdId())).isPresent();
    }

    @Test
    void 대기_중인_토큰은_좌석_조회와_선점이_403() {
        TestApp small = new TestApp(NOW, 1);
        long show = small.store.addShow("판매 중", Genre.CONCERT, NOW.minus(Duration.ofDays(1)));
        long p = small.store.addPerformance(show, NOW.plus(Duration.ofDays(3)));
        small.queue.enter(small.user("first@x.com"), p);
        long waiterId = small.user("waiter@x.com");
        String waiting = small.queue.enter(waiterId, p).queueToken();

        assertThatThrownBy(() -> small.seatService.seatMap(waiterId, waiting, p))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_NOT_ADMITTED);
        assertThatThrownBy(() -> small.seatService.hold(waiterId, waiting, p, List.of(small.store.seatIds(p).get(0))))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_NOT_ADMITTED);
    }
}
