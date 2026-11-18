package com.ticketing.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.ticketing.auth.AuthService;
import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.queue.QueueService;
import com.ticketing.queue.RedisKeys;
import com.ticketing.reservation.ReservationService;
import com.ticketing.reservation.ReservationView;
import com.ticketing.seat.SeatService;
import com.ticketing.seat.SeatViews;

/**
 * 실제 MySQL·Redis: 좌석 선점 동시성과 DB 최종 방어선(v3: 사용자마다 로그인 계정과 대기열 토큰).
 * 접속 정보는 환경변수(DB_NAME=ticketing_test, REDIS_DATABASE=1 …)로 받는다. README 2장 참고.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=true", "app.queue.max-active=100"})
class SeatConcurrencyIT {

    @Autowired
    QueueService queue;
    @Autowired
    SeatService seats;
    @Autowired
    ReservationService reservations;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StringRedisTemplate redis;

    private long perf;
    private List<Long> seatIds;

    private record Buyer(long userId, String token) {
    }

    private Buyer buyer() {
        long u = ItData.user(auth);
        return new Buyer(u, queue.enter(u, perf).queueToken());
    }

    @BeforeEach
    void setUp() {
        ItData.flushRedis(redis);
        Instant now = Instant.now();
        perf = ItData.performance(jdbc, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));
        seatIds = ItData.seatIds(jdbc, perf);
    }

    @Test
    void 같은_좌석을_동시에_50개_요청이_선점하면_정확히_1건만_성공한다() throws Exception {
        int n = 50;
        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            buyers.add(buyer()); // 계정 50개, max-active 100 → 모두 입장
        }
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        for (Buyer b : buyers) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    seats.hold(b.userId(), b.token(), perf, List.of(seatIds.get(0)));
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if (e.code() == ErrorCode.SEAT_TAKEN) {
                        taken.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(taken.get()).isEqualTo(49);
        assertThat(redis.opsForValue().get(RedisKeys.seatHold(perf, seatIds.get(0)))).isNotNull();
    }

    @Test
    void Redis_선점_키를_지운_뒤_같은_좌석을_두_요청이_확정해도_DB에서_1건만_성공한다() throws Exception {
        Buyer alice = buyer();
        Buyer bob = buyer();
        SeatViews.HoldView aliceHold = seats.hold(alice.userId(), alice.token(), perf,
                List.of(seatIds.get(0), seatIds.get(1)));
        // Redis 선점 키 강제 삭제 → bob도 같은 좌석을 선점할 수 있게 된다(Redis가 틀린 상황)
        seatIds.subList(0, 2).forEach(id -> redis.delete(RedisKeys.seatHold(perf, id)));
        SeatViews.HoldView bobHold = seats.hold(bob.userId(), bob.token(), perf, List.of(seatIds.get(1), seatIds.get(2)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Object> a = pool.submit(() -> {
            start.await();
            return attempt(alice, aliceHold.holdId());
        });
        Future<Object> b = pool.submit(() -> {
            start.await();
            return attempt(bob, bobHold.holdId());
        });
        start.countDown();
        List<Object> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results).filteredOn(r -> r instanceof ReservationView).hasSize(1);
        assertThat(results).filteredOn(r -> r == ErrorCode.SEAT_TAKEN).hasSize(1);
        int seat1Sales = jdbc.sql("SELECT COUNT(*) FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id"
                        + " WHERE rs.seat_id = :s AND r.status = 'CONFIRMED'")
                .param("s", seatIds.get(1)).query(Integer.class).single();
        assertThat(seat1Sales).isEqualTo(1);
        assertThat(ItData.soldCount(jdbc, perf)).isEqualTo(2); // 성공한 쪽의 2석만. 실패 쪽은 롤백
    }

    private Object attempt(Buyer b, String holdId) {
        try {
            return reservations.confirm(b.userId(), b.token(), UUID.randomUUID().toString(), holdId);
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void 같은_계정이_두_번에_나눠_5석을_확정하면_두_번째가_거부된다() {
        long u = ItData.user(auth);
        String t1 = queue.enter(u, perf).queueToken();
        reservations.confirm(u, t1, UUID.randomUUID().toString(),
                seats.hold(u, t1, perf, List.of(seatIds.get(0), seatIds.get(1), seatIds.get(2))).holdId());
        String t2 = queue.enter(u, perf).queueToken();
        String h2 = seats.hold(u, t2, perf, List.of(seatIds.get(3), seatIds.get(4))).holdId();

        assertThatThrownBy(() -> reservations.confirm(u, t2, UUID.randomUUID().toString(), h2))
                .extracting("code").isEqualTo(ErrorCode.BOOKING_LIMIT_EXCEEDED);
        assertThat(ItData.soldCount(jdbc, perf)).isEqualTo(3);
    }

    @Test
    void 선점은_실제_Lua에서도_전부_아니면_전무이고_다시_고르면_이전_선점이_풀린다() {
        Buyer alice = buyer();
        Buyer bob = buyer();
        seats.hold(alice.userId(), alice.token(), perf, List.of(seatIds.get(0), seatIds.get(1)));

        assertThatThrownBy(() -> seats.hold(bob.userId(), bob.token(), perf, List.of(seatIds.get(1), seatIds.get(2))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(((SeatService.SeatTaken) e.details()).seatIds())
                        .containsExactly(seatIds.get(1)));
        assertThat(redis.hasKey(RedisKeys.seatHold(perf, seatIds.get(2)))).isFalse();

        SeatViews.HoldView second = seats.hold(alice.userId(), alice.token(), perf, List.of(seatIds.get(1), seatIds.get(3)));
        assertThat(redis.hasKey(RedisKeys.seatHold(perf, seatIds.get(0)))).isFalse();
        assertThat(redis.opsForValue().get(RedisKeys.seatHold(perf, seatIds.get(1)))).isEqualTo(second.holdId());
        Long ttl = redis.getExpire(RedisKeys.seatHold(perf, seatIds.get(3)), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(290L, 300L);

        SeatViews.SeatMap bobView = seats.seatMap(bob.userId(), bob.token(), perf);
        assertThat(bobView.seats().get(1).status()).isEqualTo(SeatViews.SeatStatus.HELD);
        assertThat(bobView.myHold()).isNull();
        assertThat(seats.seatMap(alice.userId(), alice.token(), perf).seats().get(1).status())
                .isEqualTo(SeatViews.SeatStatus.MINE);
        // 다른 계정이 alice의 토큰을 쓰면 거부(토큰은 계정에 묶임)
        assertThatThrownBy(() -> seats.seatMap(bob.userId(), alice.token(), perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }
}
