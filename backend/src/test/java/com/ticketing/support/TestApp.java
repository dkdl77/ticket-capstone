package com.ticketing.support;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.ticketing.auth.AuthService;
import com.ticketing.config.AppProperties;
import com.ticketing.queue.QueueService;
import com.ticketing.queue.QueueTokens;
import com.ticketing.reservation.ReservationCodeGenerator;
import com.ticketing.reservation.ReservationService;
import com.ticketing.seat.SeatService;
import com.ticketing.show.ShowService;

/** 가짜 저장소를 주입해 서비스들을 조립한다(운영의 Spring 조립과 같은 생성자 주입). BCrypt는 테스트 속도를 위해 cost 4. */
public final class TestApp {

    public final InMemoryStore store;
    public final InMemoryStore.Shows shows;
    public final InMemoryStore.Seats seats;
    public final InMemoryStore.Reservations reservations;
    public final InMemoryStore.Queue queueStore;
    public final InMemoryStore.Holds holds;
    public final InMemoryStore.Users users;
    public final InMemoryStore.LoginAttempts attempts;
    public final InMemoryStore.Idempotency idempotency = new InMemoryStore.Idempotency();
    public final AppProperties props;
    public final AuthService auth;
    public final ShowService showService;
    public final QueueService queue;
    public final SeatService seatService;
    public final ReservationService reservationService;

    public TestApp(Instant now, int maxActive) {
        store = new InMemoryStore(now);
        shows = store.new Shows();
        seats = store.new Seats();
        reservations = store.new Reservations();
        queueStore = store.new Queue();
        holds = store.new Holds();
        users = store.new Users();
        attempts = store.new LoginAttempts();
        props = InMemoryStore.props(maxActive);
        QueueTokens tokens = new QueueTokens();
        auth = new AuthService(users, attempts, new BCryptPasswordEncoder(4));
        showService = new ShowService(shows, store.clock);
        queue = new QueueService(shows, seats, queueStore, holds, tokens, props, store.clock);
        seatService = new SeatService(shows, seats, holds, queue, props, store.clock);
        reservationService = new ReservationService(shows, seats, reservations, holds, queue, auth, idempotency,
                store.new SnapshotTransactions(), store.clock, new ReservationCodeGenerator(), Duration.ofMillis(300));
    }

    /** 가입된 사용자 id. */
    public long user(String email) {
        return auth.signup(email, "password-123", "사용자 " + email.substring(0, 1)).id();
    }
}
