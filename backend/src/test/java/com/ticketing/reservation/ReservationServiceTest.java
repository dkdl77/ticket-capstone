package com.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.queue.QueueStore;
import com.ticketing.seat.SeatRepository;
import com.ticketing.seat.SeatService;
import com.ticketing.seat.SeatViews;
import com.ticketing.show.Genre;
import com.ticketing.support.TestApp;

class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private TestApp app;
    private long perf;
    private List<Long> seats; // A1..A4 (R석 10만), B1..B4 (S석 5만)
    private long alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        app = new TestApp(NOW, 1);
        long show = app.store.addShow("판매 중", Genre.MUSICAL, NOW.minus(Duration.ofDays(1)));
        perf = app.store.addPerformance(show, NOW.plus(Duration.ofDays(3)));
        seats = app.store.seatIds(perf);
        alice = app.auth.signup("Alice.Kim@Example.com", "password-123", "  김앨리스 ").id();
        aliceToken = app.queue.enter(alice, perf).queueToken();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private SeatViews.HoldView hold(long user, String token, Long... seatIds) {
        return app.seatService.hold(user, token, perf, List.of(seatIds));
    }

    /** 대기열에 다시 들어가 입장한 뒤 좌석을 잡고 확정한다(입장 인원 1명이라 앞 사람이 끝나야 들어간다). */
    private ReservationView book(long user, Long... seatIds) {
        String token = app.queue.enter(user, perf).queueToken();
        return app.reservationService.confirm(user, token, key(), hold(user, token, seatIds).holdId());
    }

    @Test
    void 확정하면_좌석이_SOLD가_되고_이름_이메일은_계정에서_오며_입장권을_반납한다() {
        long bob = app.user("bob@x.com");
        String bobToken = app.queue.enter(bob, perf).queueToken(); // 대기
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0), seats.get(5));

        ReservationView r = app.reservationService.confirm(alice, aliceToken, key(), h.holdId());

        assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(r.code()).hasSize(12);
        assertThat(r.totalPrice()).isEqualTo(150_000);
        assertThat(r.seats()).extracting(ReservationView.SeatLine::row, ReservationView.SeatLine::number,
                ReservationView.SeatLine::grade).containsExactly(tuple("A", 1, "R"), tuple("B", 2, "S"));
        assertThat(r.name()).isEqualTo("김앨리스");
        assertThat(r.emailMasked()).isEqualTo("al***@example.com");
        assertThat(app.store.seats.get(seats.get(0)).status).isEqualTo(SeatRepository.Status.SOLD);
        assertThat(app.holds.find(h.holdId())).isEmpty();
        assertThatThrownBy(() -> app.queue.status(alice, aliceToken))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThat(app.queue.status(bob, bobToken).status()).isEqualTo(QueueStore.State.ADMITTED);
    }

    @Test
    void 같은_Idempotency_Key는_처음_응답을_그대로_입장권_반납_뒤에도() {
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0));
        String k = key();
        ReservationView first = app.reservationService.confirm(alice, aliceToken, k, h.holdId());
        ReservationView second = app.reservationService.confirm(alice, aliceToken, k, h.holdId());

        assertThat(second).isEqualTo(first);
        assertThat(app.store.reservations).hasSize(1);
    }

    @Test
    void 멱등키는_계정별이라_남의_키를_재사용해도_남의_예매를_받아_가지_못한다() {
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0));
        String k = key();
        app.reservationService.confirm(alice, aliceToken, k, h.holdId());

        long mallory = app.user("mallory@x.com");
        assertThatThrownBy(() -> app.reservationService.confirm(mallory, aliceToken, k, h.holdId()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }

    @Test
    void 회차당_계정_합산_4석을_넘으면_BOOKING_LIMIT_EXCEEDED이고_아무것도_팔리지_않는다() {
        app.reservationService.confirm(alice, aliceToken, key(),
                hold(alice, aliceToken, seats.get(0), seats.get(1), seats.get(2)).holdId());

        String again = app.queue.enter(alice, perf).queueToken();
        SeatViews.HoldView h = hold(alice, again, seats.get(3), seats.get(4));

        assertThatThrownBy(() -> app.reservationService.confirm(alice, again, key(), h.holdId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.BOOKING_LIMIT_EXCEEDED);
                    assertThat(((ReservationService.BookingLimit) e.details()).remainingAllowance()).isEqualTo(1);
                });
        assertThat(app.store.seats.get(seats.get(3)).status).isEqualTo(SeatRepository.Status.AVAILABLE);
        // 1석은 아직 가능, 취소하면 한도가 돌아온다
        assertThat(app.reservationService.confirm(alice, again, key(), hold(alice, again, seats.get(3)).holdId())
                .seats()).hasSize(1);
    }

    @Test
    void 취소한_예매는_한도에서_빠진다() {
        ReservationView four = app.reservationService.confirm(alice, aliceToken, key(),
                hold(alice, aliceToken, seats.get(0), seats.get(1), seats.get(2), seats.get(3)).holdId());
        app.reservationService.cancel(alice, four.code());

        assertThat(book(alice, seats.get(4), seats.get(5), seats.get(6), seats.get(7)).seats()).hasSize(4);
    }

    @Test
    void 선점이_만료되면_HOLD_EXPIRED() {
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0));
        app.store.clock.advance(Duration.ofSeconds(301));

        assertThatThrownBy(() -> app.reservationService.confirm(alice, aliceToken, key(), h.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(app.store.seats.get(seats.get(0)).status).isEqualTo(SeatRepository.Status.AVAILABLE);
    }

    @Test
    void 남의_holdId로는_확정할_수_없다() {
        TestApp two = new TestApp(NOW, 5);
        long show = two.store.addShow("판매 중", Genre.MUSICAL, NOW.minus(Duration.ofDays(1)));
        long p = two.store.addPerformance(show, NOW.plus(Duration.ofDays(3)));
        long a = two.user("a@x.com");
        long b = two.user("b@x.com");
        String at = two.queue.enter(a, p).queueToken();
        String bt = two.queue.enter(b, p).queueToken();
        SeatViews.HoldView aliceHold = two.seatService.hold(a, at, p, List.of(two.store.seatIds(p).get(0)));

        assertThatThrownBy(() -> two.reservationService.confirm(b, bt, key(), aliceHold.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(two.store.reservations).isEmpty();
    }

    @Test
    void Redis_선점이_틀려도_DB에서_이미_팔린_좌석은_SEAT_TAKEN으로_막고_롤백한다() {
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0), seats.get(1));
        app.store.sell(seats.get(1)); // 다른 경로로 이미 팔림(Redis는 모름)

        assertThatThrownBy(() -> app.reservationService.confirm(alice, aliceToken, key(), h.holdId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SEAT_TAKEN);
                    assertThat(((SeatService.SeatTaken) e.details()).seatIds()).containsExactly(seats.get(1));
                });
        assertThat(app.holds.find(h.holdId())).isEmpty();
        assertThat(app.store.seats.get(seats.get(0)).status).isEqualTo(SeatRepository.Status.AVAILABLE);
        assertThat(app.store.reservations).isEmpty();
    }

    @Test
    void 저장_중_실패하면_멱등키를_풀어_같은_키로_재시도할_수_있다() {
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0));
        app.reservations.failNextInsertSeats = true;
        String k = key();

        assertThatThrownBy(() -> app.reservationService.confirm(alice, aliceToken, k, h.holdId()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(app.idempotency.state).doesNotContainKey(alice + ":" + k);
    }

    @Test
    void 내_예매는_최근_순이고_취소_건도_포함되며_남의_예매는_보이지_않는다() {
        ReservationView first = app.reservationService.confirm(alice, aliceToken, key(),
                hold(alice, aliceToken, seats.get(0)).holdId());
        app.store.clock.advance(Duration.ofMinutes(1));
        ReservationView second = book(alice, seats.get(1));
        app.reservationService.cancel(alice, first.code());
        long bob = app.user("bob@x.com");
        app.store.clock.advance(Duration.ofMinutes(1));
        book(bob, seats.get(2));

        List<ReservationView> mine = app.reservationService.myReservations(alice);
        assertThat(mine).extracting(ReservationView::code).containsExactly(second.code(), first.code());
        assertThat(mine).extracting(ReservationView::status)
                .containsExactly(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED);
        assertThat(mine.get(0).seats()).hasSize(1);
        assertThat(app.reservationService.myReservations(bob)).hasSize(1);
    }

    @Test
    void 남의_예매는_취소할_수_없고_존재를_드러내지_않는_404() {
        ReservationView r = app.reservationService.confirm(alice, aliceToken, key(),
                hold(alice, aliceToken, seats.get(0)).holdId());
        long bob = app.user("bob@x.com");

        assertThatThrownBy(() -> app.reservationService.cancel(bob, r.code()))
                .extracting("code").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        assertThatThrownBy(() -> app.reservationService.cancel(bob, "ZZZZZZZZZZZZ"))
                .extracting("code").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        assertThat(app.store.seats.get(seats.get(0)).status).isEqualTo(SeatRepository.Status.SOLD);
    }

    @Test
    void 취소하면_좌석이_AVAILABLE로_돌아오고_두_번째_취소는_ALREADY_CANCELLED_시작_후에는_PERFORMANCE_CLOSED() {
        ReservationView created = app.reservationService.confirm(alice, aliceToken, key(),
                hold(alice, aliceToken, seats.get(0), seats.get(1)).holdId());
        app.store.clock.advance(Duration.ofMinutes(5));

        ReservationView cancelled = app.reservationService.cancel(alice, created.code().toLowerCase());
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(cancelled.seats()).hasSize(2);
        assertThat(app.store.seats.get(seats.get(0)).status).isEqualTo(SeatRepository.Status.AVAILABLE);
        assertThatThrownBy(() -> app.reservationService.cancel(alice, created.code()))
                .extracting("code").isEqualTo(ErrorCode.ALREADY_CANCELLED);

        ReservationView later = book(alice, seats.get(2));
        app.store.clock.advance(Duration.ofDays(4));
        assertThatThrownBy(() -> app.reservationService.cancel(alice, later.code()))
                .extracting("code").isEqualTo(ErrorCode.PERFORMANCE_CLOSED);
    }

    @Test
    void 대기_중_토큰으로는_확정할_수_없다() {
        long bob = app.user("bob@x.com");
        String waiting = app.queue.enter(bob, perf).queueToken();
        SeatViews.HoldView h = hold(alice, aliceToken, seats.get(0));
        assertThatThrownBy(() -> app.reservationService.confirm(bob, waiting, key(), h.holdId()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_NOT_ADMITTED);
        assertThatThrownBy(() -> app.reservationService.confirm(alice, null, key(), h.holdId()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }
}
