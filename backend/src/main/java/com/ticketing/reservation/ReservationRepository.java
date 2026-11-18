package com.ticketing.reservation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ticketing.seat.SeatRepository;

public interface ReservationRepository {

    /** 저장 후 생성된 id. 예매번호가 겹치면 DuplicateKeyException. */
    long insert(NewReservation reservation);

    /** 예매 당시 좌석·가격 기록. */
    void insertSeats(long reservationId, List<SeatRepository.PricedSeat> seats);

    /**
     * 트랜잭션 안에서 호출. 사용자 행을 FOR UPDATE로 잠근 뒤 이 회차에서 이 사용자가 가진 확정 좌석 수를 센다.
     * 같은 사용자의 확정이 한 줄로 세워지므로 동시에 두 번 확정해도 4석 제한이 지켜진다.
     */
    int lockUserAndCountConfirmedSeats(long userId, long performanceId);

    /** 이 사용자의 예매(최근 순, 취소 포함). */
    List<ReservationRow> findByUser(long userId);

    /** 트랜잭션 안에서 호출. 내 예매일 때만 찾고 행을 잠근다(남의 것은 비어 있음 = 404). */
    Optional<ReservationRow> findByCodeAndUserForUpdate(String code, long userId);

    /** reservationId → 좌석 목록(행 → 번호 순). 목록 화면용으로 한 번에 가져온다. */
    Map<Long, List<ReservationView.SeatLine>> findSeats(Collection<Long> reservationIds);

    void markCancelled(long id, Instant cancelledAt);
}
