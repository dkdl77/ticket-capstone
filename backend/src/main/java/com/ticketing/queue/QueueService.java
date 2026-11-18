package com.ticketing.queue;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.config.AppProperties;
import com.ticketing.seat.HoldStore;
import com.ticketing.seat.SeatRepository;
import com.ticketing.show.PerformanceInfo;
import com.ticketing.show.SaleStatus;
import com.ticketing.show.ShowRepository;

/**
 * 접속 대기열(ARCHITECTURE.md 3.2.1). 서버는 상태를 갖지 않고, 모든 상태는 Redis에 있다.
 * 토큰은 헤더(X-Queue-Token)로만 받고 로그에 남기지 않는다.
 * v3: 로그인한 사용자만 진입할 수 있고, 토큰은 그 사용자에게 묶인다. 다른 계정이 쓰면 401.
 * 한 사용자는 회차당 토큰 1개만 가진다(다시 진입하면 이전 토큰·선점은 반납) → 한 사람이 입장 자리를 여러 개 차지하지 못한다.
 */
@Service
public class QueueService {

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final QueueStore store;
    private final HoldStore holds;
    private final QueueTokens tokens;
    private final AppProperties.Queue settings;
    private final Clock clock;

    public QueueService(ShowRepository shows, SeatRepository seats, QueueStore store, HoldStore holds,
            QueueTokens tokens, AppProperties props, Clock clock) {
        this.shows = shows;
        this.seats = seats;
        this.store = store;
        this.holds = holds;
        this.tokens = tokens;
        this.settings = props.queue();
        this.clock = clock;
    }

    public QueueState enter(long userId, long performanceId) {
        PerformanceInfo perf = shows.findPerformance(performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND));
        SaleStatus timeStatus = perf.timeStatus(clock.instant());
        if (timeStatus == SaleStatus.UPCOMING) {
            throw new ApiException(ErrorCode.NOT_ON_SALE, "아직 예매가 열리지 않은 회차입니다.");
        }
        if (timeStatus == SaleStatus.CLOSED) {
            throw new ApiException(ErrorCode.NOT_ON_SALE, "이미 시작했거나 종료된 회차입니다.");
        }
        if (seats.countAvailable(performanceId) == 0) {
            throw new ApiException(ErrorCode.SOLD_OUT);
        }
        // 같은 사용자의 이전 토큰(다른 탭 등)은 반납: 선점 해제 → 대기열에서 제거
        store.tokenOf(performanceId, userId).ifPresent(previous -> {
            holds.releaseCurrent(previous);
            store.leave(previous, settings);
        });
        String token = tokens.newToken();
        QueueStore.TokenState state = store.enter(performanceId, userId, QueueTokens.tokenId(token), settings);
        return view(token, state);
    }

    public QueueState status(long userId, String token) {
        QueueStore.TokenState state = store.poll(tokenIdOrThrow(token), settings)
                .filter(s -> s.userId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUEUE_TOKEN_INVALID));
        return view(null, state);
    }

    /** 대기·입장 포기. 선점도 해제한다. 없는 토큰·남의 토큰은 조용히 무시(204). */
    public void leave(long userId, String token) {
        if (token == null || !QueueTokens.TOKEN_FORMAT.matcher(token).matches()) {
            return;
        }
        String tokenId = QueueTokens.tokenId(token);
        boolean mine = store.peek(tokenId).map(s -> s.userId() == userId).orElse(false);
        if (!mine) {
            return;
        }
        holds.releaseCurrent(tokenId);
        store.leave(tokenId, settings);
    }

    /**
     * 좌석 조회·선점·확정 전 서버 측 입장 확인(클라이언트 화면 전환만 믿지 않는다).
     * 토큰 없음·만료·다른 회차·다른 계정의 토큰 → 401, 아직 대기 중 → 403.
     * performanceId가 null이면 회차 일치 확인을 건너뛴다(확정은 선점에서 회차를 확인).
     */
    public QueueStore.TokenState requireAdmitted(long userId, String token, Long performanceId) {
        QueueStore.TokenState state = requireOwned(userId, token);
        if (performanceId != null && state.performanceId() != performanceId) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_INVALID, "이 회차의 대기열 토큰이 아닙니다. 예매하기를 다시 눌러 주세요.");
        }
        if (state.state() != QueueStore.State.ADMITTED) {
            throw new ApiException(ErrorCode.QUEUE_NOT_ADMITTED);
        }
        return state;
    }

    /** 내 토큰인지만 확인(입장 여부와 무관). */
    public QueueStore.TokenState requireOwned(long userId, String token) {
        return store.peek(tokenIdOrThrow(token))
                .filter(s -> s.userId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUEUE_TOKEN_INVALID));
    }

    /** 예매 완료: 입장 권한 반납 → 다음 대기자 승격. */
    public void finish(String token) {
        store.leave(QueueTokens.tokenId(token), settings);
    }

    public static String tokenIdOrThrow(String token) {
        if (token == null || !QueueTokens.TOKEN_FORMAT.matcher(token).matches()) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_INVALID);
        }
        return QueueTokens.tokenId(token);
    }

    private QueueState view(String token, QueueStore.TokenState s) {
        return new QueueState(token, s.state(), s.position(), s.admittedUntil(), pollAfterSeconds(s));
    }

    /**
     * 권장 폴링 간격. 앞사람이 적을수록 자주(빨리 입장 화면으로), 많을수록 드물게(서버 부하).
     * 대기 TTL 안에 최소 3번은 폴링하도록 상한을 둔다.
     */
    int pollAfterSeconds(QueueStore.TokenState s) {
        if (s.state() == QueueStore.State.ADMITTED) {
            return 5;
        }
        int base = s.position() < 10 ? 2 : s.position() < 100 ? 3 : 5;
        int cap = Math.max(1, settings.waitTtlSeconds() / 3);
        return Math.min(base, cap);
    }
}
