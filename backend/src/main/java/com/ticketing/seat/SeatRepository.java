package com.ticketing.seat;

import java.util.Collection;
import java.util.List;

public interface SeatRepository {

    enum Status { AVAILABLE, SOLD }

    record SeatRow(long id, String row, int number, String grade, Status status) {
    }

    record PricedSeat(long id, String row, int number, String grade, int price, Status status) {
    }

    /** 회차의 모든 좌석(행 → 번호 순). 좌석 화면 폴링용이라 쿼리 한 번으로 끝난다. */
    List<SeatRow> findByPerformance(long performanceId);

    /** 이 회차에 속한 좌석만 가격과 함께 돌려준다(없는 id는 빠짐). */
    List<PricedSeat> findPriced(long performanceId, Collection<Long> seatIds);

    int countAvailable(long performanceId);

    /**
     * 판매 처리: UPDATE ... WHERE status = 'AVAILABLE'. 반환값 = 실제로 바뀐 행 수.
     * 호출자는 이 값이 좌석 수와 같은지 확인하고, 다르면 트랜잭션을 롤백한다(중복 판매 최종 방어선).
     */
    int markSold(long performanceId, Collection<Long> seatIds, long reservationId);

    /** 취소: 이 예매가 가진 좌석을 다시 AVAILABLE로. */
    int releaseByReservation(long reservationId);
}
