package com.ticketing.seat;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSeatRepository implements SeatRepository {

    private final JdbcClient jdbc;

    public JdbcSeatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SeatRow> findByPerformance(long performanceId) {
        return jdbc.sql("""
                        SELECT id, row_label, seat_number, grade_code, status
                          FROM seats
                         WHERE performance_id = :p
                         ORDER BY CHAR_LENGTH(row_label), row_label, seat_number
                        """)
                .param("p", performanceId)
                .query((rs, n) -> new SeatRow(rs.getLong("id"), rs.getString("row_label"), rs.getInt("seat_number"),
                        rs.getString("grade_code"), Status.valueOf(rs.getString("status"))))
                .list();
    }

    @Override
    public List<PricedSeat> findPriced(long performanceId, Collection<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT s.id, s.row_label, s.seat_number, s.grade_code, g.price, s.status
                          FROM seats s
                          JOIN performances p ON p.id = s.performance_id
                          JOIN grades g ON g.show_id = p.show_id AND g.code = s.grade_code
                         WHERE s.performance_id = :p AND s.id IN (:ids)
                         ORDER BY CHAR_LENGTH(s.row_label), s.row_label, s.seat_number
                        """)
                .param("p", performanceId)
                .param("ids", seatIds)
                .query((rs, n) -> new PricedSeat(rs.getLong("id"), rs.getString("row_label"), rs.getInt("seat_number"),
                        rs.getString("grade_code"), rs.getInt("price"), Status.valueOf(rs.getString("status"))))
                .list();
    }

    @Override
    public int countAvailable(long performanceId) {
        return jdbc.sql("SELECT COUNT(*) FROM seats WHERE performance_id = :p AND status = 'AVAILABLE'")
                .param("p", performanceId).query(Integer.class).single();
    }

    @Override
    public int markSold(long performanceId, Collection<Long> seatIds, long reservationId) {
        return jdbc.sql("""
                        UPDATE seats SET status = 'SOLD', reservation_id = :r
                         WHERE performance_id = :p AND id IN (:ids) AND status = 'AVAILABLE'
                        """)
                .param("r", reservationId)
                .param("p", performanceId)
                .param("ids", seatIds)
                .update();
    }

    @Override
    public int releaseByReservation(long reservationId) {
        return jdbc.sql("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL WHERE reservation_id = :r")
                .param("r", reservationId).update();
    }
}
