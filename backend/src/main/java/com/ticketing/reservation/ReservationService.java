package com.ticketing.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.ticketing.auth.AuthService;
import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.common.Transactions;
import com.ticketing.queue.QueueService;
import com.ticketing.queue.QueueStore;
import com.ticketing.seat.HoldStore;
import com.ticketing.seat.SeatRepository;
import com.ticketing.seat.SeatService;
import com.ticketing.show.PerformanceInfo;
import com.ticketing.show.SaleStatus;
import com.ticketing.show.ShowRepository;
import com.ticketing.show.ShowService;

/**
 * 예매 확정·내 예매·취소(ARCHITECTURE.md 3.2.2, 3.2.3).
 * <pre>
 * 1) Idempotency-Key 선점 — 키는 사용자별로 나눈다(남의 키로 남의 예매 응답을 받아 갈 수 없게)
 * 2) 대기열 ADMITTED + 내 토큰 확인 → 선점이 내 토큰·내 계정·같은 회차인지 → 판매 기간
 * 3) MySQL 트랜잭션:
 *    사용자 행 잠금 → 이 회차의 내 확정 좌석 수 + 이번 좌석 수 > 4 → BOOKING_LIMIT_EXCEEDED
 *    예매 INSERT(user_id, 계정의 이름·이메일) → UPDATE seats ... WHERE status='AVAILABLE'
 *    영향 행 수 ≠ 좌석 수면 롤백 → SEAT_TAKEN  (Redis 선점이 틀리거나 비어도 중복 판매 불가)
 * 4) 커밋 후 선점 삭제 + 입장 권한 반납(다음 대기자 승격)
 * </pre>
 * 로그에는 이름·이메일·토큰을 남기지 않는다.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_CODE_ATTEMPTS = 3;
    private static final long POLL_MILLIS = 100;

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final HoldStore holds;
    private final QueueService queue;
    private final AuthService auth;
    private final IdempotencyStore idempotency;
    private final Transactions tx;
    private final Clock clock;
    private final ReservationCodeGenerator codes;
    private final Duration inProgressWait;

    public ReservationService(ShowRepository shows, SeatRepository seats, ReservationRepository reservations,
            HoldStore holds, QueueService queue, AuthService auth, IdempotencyStore idempotency, Transactions tx,
            Clock clock, ReservationCodeGenerator codes,
            @Value("${app.idempotency.in-progress-wait:5s}") Duration inProgressWait) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.holds = holds;
        this.queue = queue;
        this.auth = auth;
        this.idempotency = idempotency;
        this.tx = tx;
        this.clock = clock;
        this.codes = codes;
        this.inProgressWait = inProgressWait;
    }

    public ReservationView confirm(long userId, String token, String idempotencyKey, String holdId) {
        String key = userId + ":" + idempotencyKey;
        long deadline = System.nanoTime() + inProgressWait.toNanos();
        while (true) {
            // 멱등 확인이 먼저: 완료 후에는 입장 권한이 반납되므로, 재전송은 토큰 확인 전에 처음 응답으로 답한다.
            switch (idempotency.claim(key)) {
                case IdempotencyStore.Completed done -> {
                    return done.response();
                }
                case IdempotencyStore.Acquired acquired -> {
                    return confirmOwned(userId, token, key, holdId);
                }
                case IdempotencyStore.InProgress inProgress -> {
                    if (System.nanoTime() >= deadline) {
                        throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS);
                    }
                    sleep();
                }
            }
        }
    }

    private ReservationView confirmOwned(long userId, String token, String key, String holdId) {
        ReservationView view;
        try {
            view = createReservation(userId, token, holdId);
        } catch (RuntimeException e) {
            // 실패(4xx·5xx)는 멱등 결과로 저장하지 않는다 → 같은 키로 재시도 가능
            releaseQuietly(key);
            throw e;
        }
        try {
            idempotency.complete(key, view);
        } catch (RuntimeException e) {
            log.warn("멱등 응답 저장 실패: {}", e.getClass().getSimpleName());
        }
        return view;
    }

    private ReservationView createReservation(long userId, String token, String holdId) {
        QueueStore.TokenState admitted = queue.requireAdmitted(userId, token, null);
        String tokenId = QueueService.tokenIdOrThrow(token);

        HoldStore.Hold hold = holds.find(holdId)
                .filter(h -> h.tokenId().equals(tokenId) && h.userId() == userId
                        && h.performanceId() == admitted.performanceId())
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_EXPIRED));
        PerformanceInfo perf = shows.findPerformance(hold.performanceId())
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_EXPIRED));
        Instant now = now();
        if (perf.timeStatus(now) != SaleStatus.ON_SALE) {
            throw new ApiException(ErrorCode.NOT_ON_SALE);
        }
        List<SeatRepository.PricedSeat> priced = seats.findPriced(perf.id(), hold.seatIds());
        if (priced.size() != hold.seatIds().size()) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED);
        }
        AuthService.User user = auth.findUser(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        int total = priced.stream().mapToInt(SeatRepository.PricedSeat::price).sum();
        String emailHash = EmailPrivacy.hash(user.email());
        String emailMasked = EmailPrivacy.mask(user.email());
        int count = hold.seatIds().size();

        ReservationRow row;
        try {
            row = tx.inTransaction(() -> {
                int already = reservations.lockUserAndCountConfirmedSeats(userId, perf.id());
                if (already + count > ShowService.MAX_SEATS_PER_BOOKING) {
                    int left = Math.max(0, ShowService.MAX_SEATS_PER_BOOKING - already);
                    throw new ApiException(ErrorCode.BOOKING_LIMIT_EXCEEDED,
                            "한 회차에는 계정당 최대 4석까지 예매할 수 있습니다. 이 회차에서 더 예매할 수 있는 좌석은 " + left + "석입니다.",
                            new BookingLimit(already, left));
                }
                String code = null;
                long id = 0;
                for (int attempt = 1; ; attempt++) {
                    code = codes.next();
                    try {
                        id = reservations.insert(new NewReservation(code, perf.id(), userId, total, user.name(),
                                emailHash, emailMasked, now));
                        break;
                    } catch (DuplicateKeyException e) {
                        if (attempt >= MAX_CODE_ATTEMPTS) {
                            throw e;
                        }
                    }
                }
                int updated = seats.markSold(perf.id(), hold.seatIds(), id);
                if (updated != count) {
                    throw new SeatConflict(); // 롤백
                }
                reservations.insertSeats(id, priced);
                return new ReservationRow(id, code, perf.id(), perf.startsAt(), perf.showId(), perf.showTitle(),
                        perf.venue(), total, user.name(), emailHash, emailMasked, ReservationStatus.CONFIRMED, now,
                        null);
            });
        } catch (SeatConflict e) {
            // Redis 선점과 무관하게 DB에서 이미 팔린 좌석이 있었다. 쓸모없어진 선점은 풀어 준다.
            List<Long> sold = seats.findPriced(perf.id(), hold.seatIds()).stream()
                    .filter(s -> s.status() == SeatRepository.Status.SOLD).map(SeatRepository.PricedSeat::id).toList();
            log.warn("DB에서 좌석 중복 판매를 막았습니다. performanceId={} seats={}", perf.id(), sold.size());
            releaseHoldQuietly(hold.holdId(), tokenId);
            throw SeatService.seatTaken(sold);
        }

        // 커밋 이후: 선점 삭제 + 입장 권한 반납. 실패해도 예매는 유효(TTL로 결국 사라진다).
        releaseHoldQuietly(hold.holdId(), tokenId);
        try {
            queue.finish(token);
        } catch (RuntimeException e) {
            log.warn("입장 권한 반납 실패(만료 시 자동 정리): {}", e.getClass().getSimpleName());
        }
        List<ReservationView.SeatLine> lines = priced.stream()
                .map(s -> new ReservationView.SeatLine(s.row(), s.number(), s.grade(), s.price())).toList();
        return ReservationView.of(row, lines);
    }

    /** BOOKING_LIMIT_EXCEEDED의 details: 이미 가진 좌석 수와 더 예매할 수 있는 좌석 수. */
    public record BookingLimit(int alreadyBooked, int remainingAllowance) {
    }

    /** 내 예매(최근 순, 취소 포함). 좌석은 쿼리 한 번으로 모아 온다. */
    public List<ReservationView> myReservations(long userId) {
        List<ReservationRow> rows = reservations.findByUser(userId);
        Map<Long, List<ReservationView.SeatLine>> seatsById =
                reservations.findSeats(rows.stream().map(ReservationRow::id).toList());
        return rows.stream().map(r -> ReservationView.of(r, seatsById.getOrDefault(r.id(), List.of()))).toList();
    }

    /** 본인 예매만 취소. 남의 예매번호는 존재 여부를 드러내지 않고 같은 404. */
    public ReservationView cancel(long userId, String code) {
        Instant now = now();
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9]{10,20}$")) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        ReservationRow cancelled = tx.inTransaction(() -> {
            ReservationRow row = reservations.findByCodeAndUserForUpdate(normalized, userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
            if (row.status() == ReservationStatus.CANCELLED) {
                throw new ApiException(ErrorCode.ALREADY_CANCELLED);
            }
            if (!now.isBefore(row.performanceStartsAt())) {
                throw new ApiException(ErrorCode.PERFORMANCE_CLOSED);
            }
            reservations.markCancelled(row.id(), now);
            seats.releaseByReservation(row.id()); // 좌석을 다시 AVAILABLE로
            return row.cancelled(now);
        });
        return ReservationView.of(cancelled,
                reservations.findSeats(List.of(cancelled.id())).getOrDefault(cancelled.id(), List.of()));
    }

    /** DATETIME(3)과 맞추기 위해 밀리초로 자른다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void releaseHoldQuietly(String holdId, String tokenId) {
        try {
            holds.release(holdId, tokenId);
        } catch (RuntimeException e) {
            log.warn("선점 해제 실패(TTL로 자동 해제): {}", e.getClass().getSimpleName());
        }
    }

    private void releaseQuietly(String key) {
        try {
            idempotency.release(key);
        } catch (RuntimeException e) {
            log.warn("멱등키 해제 실패(60초 뒤 자동 만료): {}", e.getClass().getSimpleName());
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS);
        }
    }

    /** UPDATE 영향 행 수가 모자람 = 이미 팔린 좌석이 있음. 트랜잭션 롤백 신호. */
    private static final class SeatConflict extends RuntimeException {
        SeatConflict() {
            super(null, null, false, false);
        }
    }
}
