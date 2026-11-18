package com.ticketing.seat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;
import com.ticketing.config.AppProperties;
import com.ticketing.queue.QueueService;
import com.ticketing.queue.QueueStore;
import com.ticketing.show.PerformanceInfo;
import com.ticketing.show.SaleStatus;
import com.ticketing.show.ShowRepository;
import com.ticketing.show.ShowService;
import com.ticketing.show.ShowViews.Grade;
import com.ticketing.seat.SeatViews.HoldView;
import com.ticketing.seat.SeatViews.Seat;
import com.ticketing.seat.SeatViews.SeatMap;
import com.ticketing.seat.SeatViews.SeatStatus;

/**
 * 좌석 화면과 임시 선점(ARCHITECTURE.md 3.2.2).
 * 좌석 상태 = DB 판매 여부(쿼리 1번) + Redis 선점 여부(MGET 1번). 남의 선점은 MINE으로 보이지 않는다.
 */
@Service
public class SeatService {

    public record SeatTaken(List<Long> seatIds) {
    }

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final HoldStore holds;
    private final QueueService queue;
    private final AppProperties.Queue settings;
    private final Clock clock;

    public SeatService(ShowRepository shows, SeatRepository seats, HoldStore holds, QueueService queue,
            AppProperties props, Clock clock) {
        this.shows = shows;
        this.seats = seats;
        this.holds = holds;
        this.queue = queue;
        this.settings = props.queue();
        this.clock = clock;
    }

    public SeatMap seatMap(long userId, String token, long performanceId) {
        PerformanceInfo perf = performance(performanceId);
        queue.requireAdmitted(userId, token, performanceId);
        String tokenId = QueueService.tokenIdOrThrow(token);

        List<SeatRepository.SeatRow> rows = seats.findByPerformance(performanceId);
        Map<Long, String> holders = holds.holdersOf(performanceId, rows.stream().map(SeatRepository.SeatRow::id).toList());
        Optional<HoldStore.Hold> mine = holds.currentOf(tokenId).filter(h -> h.performanceId() == performanceId);
        String myHoldId = mine.map(HoldStore.Hold::holdId).orElse(null);

        List<Seat> out = new ArrayList<>(rows.size());
        for (SeatRepository.SeatRow r : rows) {
            SeatStatus status;
            if (r.status() == SeatRepository.Status.SOLD) {
                status = SeatStatus.SOLD;
            } else if (holders.containsKey(r.id())) {
                status = holders.get(r.id()).equals(myHoldId) ? SeatStatus.MINE : SeatStatus.HELD;
            } else {
                status = SeatStatus.AVAILABLE;
            }
            out.add(new Seat(r.id(), r.row(), r.number(), r.grade(), status));
        }
        List<Grade> grades = shows.findGrades(List.of(perf.showId())).stream()
                .map(g -> new Grade(g.code(), g.name(), g.price())).toList();
        List<String> rowLabels = new ArrayList<>();
        for (int i = 0; i < perf.seatRows(); i++) {
            rowLabels.add(rowLabel(i));
        }
        return new SeatMap(
                new SeatViews.PerformanceRef(perf.id(), perf.showId(), perf.showTitle(), perf.venue(), perf.startsAt()),
                grades, rowLabels, perf.seatsPerRow(), perf.aisleAfter(), out, mine.map(HoldView::of).orElse(null));
    }

    public HoldView hold(long userId, String token, long performanceId, List<Long> seatIds) {
        PerformanceInfo perf = performance(performanceId);
        QueueStore.TokenState admitted = queue.requireAdmitted(userId, token, performanceId);
        String tokenId = QueueService.tokenIdOrThrow(token);

        if (seatIds.size() > ShowService.MAX_SEATS_PER_BOOKING) {
            throw invalid("한 번에 최대 4석까지 선택할 수 있습니다.");
        }
        Set<Long> unique = new HashSet<>(seatIds);
        if (unique.size() != seatIds.size()) {
            throw invalid("같은 좌석을 중복해서 선택할 수 없습니다.");
        }
        if (perf.timeStatus(clock.instant()) != SaleStatus.ON_SALE) {
            throw new ApiException(ErrorCode.NOT_ON_SALE);
        }
        List<SeatRepository.PricedSeat> priced = seats.findPriced(performanceId, seatIds);
        if (priced.size() != seatIds.size()) {
            throw invalid("이 회차에 없는 좌석이 포함되어 있습니다.");
        }
        List<Long> sold = priced.stream().filter(s -> s.status() == SeatRepository.Status.SOLD)
                .map(SeatRepository.PricedSeat::id).toList();
        if (!sold.isEmpty()) {
            throw seatTaken(sold);
        }
        int total = priced.stream().mapToInt(SeatRepository.PricedSeat::price).sum();
        // 요청 순서가 아니라 좌석 순서로 저장(응답·예매 내역이 일정하게 보이도록)
        List<Long> ordered = priced.stream().map(SeatRepository.PricedSeat::id).toList();

        HoldStore.Result result = holds.create(tokenId, userId, performanceId, ordered, total, settings.hold(),
                admitted.admittedUntil());
        return switch (result) {
            case HoldStore.Created c -> HoldView.of(c.hold());
            case HoldStore.Taken t -> throw seatTaken(t.seatIds());
            case HoldStore.AdmissionEnding a -> throw new ApiException(ErrorCode.QUEUE_TOKEN_INVALID,
                    "입장 시간이 곧 끝나 좌석을 선택할 수 없습니다. 예매하기를 다시 눌러 주세요.");
        };
    }

    public void release(long userId, String token, String holdId) {
        queue.requireOwned(userId, token); // 다른 계정의 토큰이면 401
        String tokenId = QueueService.tokenIdOrThrow(token);
        if (holdId == null || !com.ticketing.queue.QueueTokens.HOLD_ID_FORMAT.matcher(holdId).matches()
                || !holds.release(holdId, tokenId)) {
            throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
        }
    }

    private PerformanceInfo performance(long performanceId) {
        return shows.findPerformance(performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND));
    }

    public static ApiException seatTaken(List<Long> seatIds) {
        return new ApiException(ErrorCode.SEAT_TAKEN, ErrorCode.SEAT_TAKEN.defaultMessage(), new SeatTaken(seatIds));
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message,
                List.of(new com.ticketing.common.ApiError.FieldViolation("seatIds", message)));
    }

    /** 0 → "A", 25 → "Z" (행은 최대 26개). */
    public static String rowLabel(int index) {
        return String.valueOf((char) ('A' + index));
    }
}
