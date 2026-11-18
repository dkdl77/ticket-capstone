package com.ticketing.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.ticketing.common.ErrorCode;
import com.ticketing.queue.QueueService;
import com.ticketing.queue.QueueState;
import com.ticketing.queue.QueueStore;
import com.ticketing.reservation.ReservationService;
import com.ticketing.seat.SeatService;

/** 실제 Redis Lua 대기열: QUEUE_MAX_ACTIVE=2, 대기 TTL 3초. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=true", "app.queue.max-active=2", "app.queue.wait-ttl-seconds=3"})
class QueueIT {

    @Autowired
    QueueService queue;
    @Autowired
    SeatService seats;
    @Autowired
    ReservationService reservations;
    @Autowired
    com.ticketing.auth.AuthService auth;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StringRedisTemplate redis;

    private long perf;

    @BeforeEach
    void setUp() {
        ItData.flushRedis(redis);
        Instant now = Instant.now();
        perf = ItData.performance(jdbc, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));
    }

    @Test
    void 다섯_명이_진입하면_두_명만_입장하고_완료와_이탈마다_대기_1번이_순서대로_승격된다() {
        List<QueueState> s = new ArrayList<>();
        List<Long> u = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            u.add(ItData.user(auth));
            s.add(queue.enter(u.get(i), perf));
        }
        assertThat(s).extracting(QueueState::status).containsExactly(
                QueueStore.State.ADMITTED, QueueStore.State.ADMITTED,
                QueueStore.State.WAITING, QueueStore.State.WAITING, QueueStore.State.WAITING);
        assertThat(s.subList(2, 5)).extracting(QueueState::position).containsExactly(0L, 1L, 2L);
        assertThat(s.get(0).admittedUntil()).isAfter(Instant.now().plusSeconds(590));

        // 1번 입장자가 예매를 완료 → 입장 권한 반납
        String first = s.get(0).queueToken();
        var hold = seats.hold(u.get(0), first, perf, List.of(ItData.seatIds(jdbc, perf).get(0)));
        reservations.confirm(u.get(0), first, UUID.randomUUID().toString(), hold.holdId());

        assertThat(queue.status(u.get(2), s.get(2).queueToken()).status()).isEqualTo(QueueStore.State.ADMITTED);
        assertThat(queue.status(u.get(3), s.get(3).queueToken()).position()).isZero();
        assertThat(queue.status(u.get(4), s.get(4).queueToken()).position()).isEqualTo(1);

        // 2번 입장자가 이탈 → 다음 대기자(원래 4번째) 승격, 5번째는 여전히 대기
        queue.leave(u.get(1), s.get(1).queueToken());
        assertThat(queue.status(u.get(3), s.get(3).queueToken()).status()).isEqualTo(QueueStore.State.ADMITTED);
        assertThat(queue.status(u.get(4), s.get(4).queueToken()).status()).isEqualTo(QueueStore.State.WAITING);
        assertThat(queue.status(u.get(4), s.get(4).queueToken()).position()).isZero();

        // 완료·이탈한 토큰은 더 이상 쓸 수 없다
        assertThatThrownBy(() -> queue.status(u.get(0), first)).extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> queue.status(u.get(1), s.get(1).queueToken()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }

    @Test
    void 대기_중_폴링이_TTL보다_오래_끊긴_사람은_빠지고_뒤_사람이_앞으로_온다() throws Exception {
        queue.enter(ItData.user(auth), perf);
        queue.enter(ItData.user(auth), perf);
        long idleUser = ItData.user(auth);
        long activeUser = ItData.user(auth);
        QueueState idle = queue.enter(idleUser, perf);
        QueueState active = queue.enter(activeUser, perf);

        for (int i = 0; i < 4; i++) { // 4초 동안 active만 1초마다 폴링
            Thread.sleep(1000);
            queue.status(activeUser, active.queueToken());
        }

        assertThat(queue.status(activeUser, active.queueToken()).position()).isZero();
        assertThatThrownBy(() -> queue.status(idleUser, idle.queueToken()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }
}
