package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.common.ErrorCode;
import com.ticketing.show.Genre;
import com.ticketing.support.TestApp;

class QueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private TestApp app;
    private long perf;
    private long a;
    private long b;
    private long c;
    private long d;

    @BeforeEach
    void setUp() {
        app = new TestApp(NOW, 2);
        long show = app.store.addShow("판매 중", Genre.CONCERT, NOW.minus(Duration.ofDays(1)));
        perf = app.store.addPerformance(show, NOW.plus(Duration.ofDays(3)));
        a = app.user("a@x.com");
        b = app.user("b@x.com");
        c = app.user("c@x.com");
        d = app.user("d@x.com");
    }

    @Test
    void 자리가_있으면_즉시_입장_없으면_대기하고_들어온_순서대로_승격된다() {
        QueueState sa = app.queue.enter(a, perf);
        QueueState sb = app.queue.enter(b, perf);
        QueueState sc = app.queue.enter(c, perf);
        QueueState sd = app.queue.enter(d, perf);

        assertThat(sa.status()).isEqualTo(QueueStore.State.ADMITTED);
        assertThat(sa.queueToken()).hasSize(43);
        assertThat(sa.admittedUntil()).isEqualTo(NOW.plusSeconds(600));
        assertThat(sb.status()).isEqualTo(QueueStore.State.ADMITTED);
        assertThat(sc.status()).isEqualTo(QueueStore.State.WAITING);
        assertThat(sc.position()).isZero();
        assertThat(sd.position()).isEqualTo(1);

        app.queue.leave(a, sa.queueToken());

        assertThat(app.queue.status(c, sc.queueToken()).status()).isEqualTo(QueueStore.State.ADMITTED);
        QueueState dNow = app.queue.status(d, sd.queueToken());
        assertThat(dNow.status()).isEqualTo(QueueStore.State.WAITING);
        assertThat(dNow.position()).isZero();
        assertThat(dNow.queueToken()).isNull(); // status 응답에는 토큰이 없다
    }

    @Test
    void 입장_시간이_지나면_자리가_비고_다음_대기자가_승격된다() {
        QueueState sa = app.queue.enter(a, perf);
        app.queue.enter(b, perf);
        QueueState sc = app.queue.enter(c, perf);

        for (int s = 20; s <= 580; s += 20) { // c는 20초마다 폴링하며 기다린다
            app.store.clock.advance(Duration.ofSeconds(20));
            assertThat(app.queue.status(c, sc.queueToken()).status()).isEqualTo(QueueStore.State.WAITING);
        }
        app.store.clock.advance(Duration.ofSeconds(20)); // 600초: a, b 입장 만료

        assertThat(app.queue.status(c, sc.queueToken()).status()).isEqualTo(QueueStore.State.ADMITTED);
        assertThatThrownBy(() -> app.queue.status(a, sa.queueToken()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
    }

    @Test
    void 대기_중_폴링이_TTL보다_오래_끊기면_대기열에서_빠진다() {
        app.queue.enter(a, perf);
        app.queue.enter(b, perf);
        QueueState sc = app.queue.enter(c, perf);
        QueueState sd = app.queue.enter(d, perf);

        app.store.clock.advance(Duration.ofSeconds(20));
        app.queue.status(d, sd.queueToken()); // d만 폴링
        app.store.clock.advance(Duration.ofSeconds(15)); // c는 35초 동안 폴링 안 함(TTL 30초)

        assertThatThrownBy(() -> app.queue.status(c, sc.queueToken()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThat(app.queue.status(d, sd.queueToken()).position()).isZero();
    }

    @Test
    void 판매_전_종료_매진_회차는_진입할_수_없다() {
        long upcomingShow = app.store.addShow("예정", Genre.PLAY, NOW.plus(Duration.ofDays(2)));
        long upcoming = app.store.addPerformance(upcomingShow, NOW.plus(Duration.ofDays(9)));
        long show = app.store.addShow("종료", Genre.PLAY, NOW.minus(Duration.ofDays(9)));
        long closed = app.store.addPerformance(show, NOW.minus(Duration.ofHours(1)));
        long soldOut = app.store.addPerformance(show, NOW.plus(Duration.ofDays(1)));
        app.store.seatIds(soldOut).forEach(app.store::sell);

        assertThatThrownBy(() -> app.queue.enter(a, upcoming)).extracting("code").isEqualTo(ErrorCode.NOT_ON_SALE);
        assertThatThrownBy(() -> app.queue.enter(a, closed)).extracting("code").isEqualTo(ErrorCode.NOT_ON_SALE);
        assertThatThrownBy(() -> app.queue.enter(a, soldOut)).extracting("code").isEqualTo(ErrorCode.SOLD_OUT);
        assertThatThrownBy(() -> app.queue.enter(a, 999_999)).extracting("code")
                .isEqualTo(ErrorCode.PERFORMANCE_NOT_FOUND);
    }

    @Test
    void 입장_확인_토큰_없음_형식오류_다른회차_다른계정은_401_대기중은_403() {
        QueueState admitted = app.queue.enter(a, perf);
        app.queue.enter(b, perf);
        QueueState waiting = app.queue.enter(c, perf);
        long otherShow = app.store.addShow("다른", Genre.MUSICAL, NOW.minus(Duration.ofDays(1)));
        long other = app.store.addPerformance(otherShow, NOW.plus(Duration.ofDays(2)));
        QueueState otherPerfToken = app.queue.enter(a, other);

        assertThatThrownBy(() -> app.queue.requireAdmitted(a, null, perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> app.queue.requireAdmitted(a, "not-a-token", perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> app.queue.requireAdmitted(a, otherPerfToken.queueToken(), perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> app.queue.requireAdmitted(c, waiting.queueToken(), perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_NOT_ADMITTED);
        // 다른 계정이 남의 토큰을 쓰면 401 (상태 조회도 마찬가지)
        assertThatThrownBy(() -> app.queue.requireAdmitted(d, admitted.queueToken(), perf))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThatThrownBy(() -> app.queue.status(d, admitted.queueToken()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        // 남의 토큰으로 이탈해도 아무 일 없음
        app.queue.leave(d, admitted.queueToken());
        assertThat(app.queue.requireAdmitted(a, admitted.queueToken(), perf).state())
                .isEqualTo(QueueStore.State.ADMITTED);
    }

    @Test
    void 같은_사용자가_다시_진입하면_이전_토큰은_무효가_되어_자리를_여러_개_차지하지_못한다() {
        QueueState first = app.queue.enter(a, perf);
        QueueState second = app.queue.enter(a, perf);
        QueueState other = app.queue.enter(b, perf); // 최대 2명 → a가 두 자리를 차지했다면 b는 대기였을 것

        assertThat(second.queueToken()).isNotEqualTo(first.queueToken());
        assertThatThrownBy(() -> app.queue.status(a, first.queueToken()))
                .extracting("code").isEqualTo(ErrorCode.QUEUE_TOKEN_INVALID);
        assertThat(other.status()).isEqualTo(QueueStore.State.ADMITTED);
    }

    @Test
    void 권장_폴링_간격은_대기_TTL_안에_세_번_이상_폴링하도록() {
        QueueService q = app.queue;
        assertThat(q.pollAfterSeconds(new QueueStore.TokenState(1, a, QueueStore.State.WAITING, 3, null))).isEqualTo(2);
        assertThat(q.pollAfterSeconds(new QueueStore.TokenState(1, a, QueueStore.State.WAITING, 50, null))).isEqualTo(3);
        assertThat(q.pollAfterSeconds(new QueueStore.TokenState(1, a, QueueStore.State.WAITING, 5000, null))).isEqualTo(5);
        assertThat(q.pollAfterSeconds(new QueueStore.TokenState(1, a, QueueStore.State.ADMITTED, 0, NOW))).isEqualTo(5);
    }

    @Test
    void 없는_토큰으로_이탈해도_조용히_성공() {
        app.queue.leave(a, null);
        app.queue.leave(a, "x".repeat(43));
    }
}
