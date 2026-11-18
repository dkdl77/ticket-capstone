package com.ticketing.reservation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.ticketing.seat.SeatRepository;

@Repository
public class JdbcReservationRepository implements ReservationRepository {

    private static final String SELECT = """
            SELECT r.id, r.code, r.performance_id, p.starts_at, s.id AS show_id, s.title, s.venue, r.total_price,
                   r.name, r.email_hash, r.email_masked, r.status, r.created_at, r.cancelled_at
              FROM reservations r
              JOIN performances p ON p.id = r.performance_id
              JOIN shows s ON s.id = p.show_id
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcReservationRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Override
    public long insert(NewReservation r) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO reservations
                               (code, performance_id, user_id, total_price, name, email_hash, email_masked, status, created_at)
                        VALUES (:code, :performanceId, :userId, :totalPrice, :name, :emailHash, :emailMasked, 'CONFIRMED',
                                :createdAt)
                        """)
                .param("code", r.code())
                .param("performanceId", r.performanceId())
                .param("userId", r.userId())
                .param("totalPrice", r.totalPrice())
                .param("name", r.name())
                .param("emailHash", r.emailHash())
                .param("emailMasked", r.emailMasked())
                .param("createdAt", Timestamp.from(r.createdAt()))
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public void insertSeats(long reservationId, List<SeatRepository.PricedSeat> seats) {
        MapSqlParameterSource[] batch = seats.stream()
                .map(s -> new MapSqlParameterSource().addValue("r", reservationId).addValue("s", s.id())
                        .addValue("price", s.price()))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate("INSERT INTO reservation_seats (reservation_id, seat_id, price) VALUES (:r, :s, :price)", batch);
    }

    @Override
    public int lockUserAndCountConfirmedSeats(long userId, long performanceId) {
        jdbc.sql("SELECT id FROM users WHERE id = :u FOR UPDATE").param("u", userId).query(Long.class).single();
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM reservation_seats rs
                          JOIN reservations r ON r.id = rs.reservation_id
                         WHERE r.user_id = :u AND r.performance_id = :p AND r.status = 'CONFIRMED'
                        """)
                .param("u", userId).param("p", performanceId).query(Integer.class).single();
    }

    @Override
    public List<ReservationRow> findByUser(long userId) {
        return jdbc.sql(SELECT + " WHERE r.user_id = :u ORDER BY r.created_at DESC, r.id DESC")
                .param("u", userId).query(JdbcReservationRepository::map).list();
    }

    @Override
    public Optional<ReservationRow> findByCodeAndUserForUpdate(String code, long userId) {
        return jdbc.sql(SELECT + " WHERE r.code = :code AND r.user_id = :u FOR UPDATE OF r")
                .param("code", code).param("u", userId).query(JdbcReservationRepository::map).optional();
    }

    @Override
    public Map<Long, List<ReservationView.SeatLine>> findSeats(Collection<Long> reservationIds) {
        Map<Long, List<ReservationView.SeatLine>> out = new HashMap<>();
        if (reservationIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT rs.reservation_id, st.row_label, st.seat_number, st.grade_code, rs.price
                          FROM reservation_seats rs JOIN seats st ON st.id = rs.seat_id
                         WHERE rs.reservation_id IN (:ids)
                         ORDER BY rs.reservation_id, CHAR_LENGTH(st.row_label), st.row_label, st.seat_number
                        """)
                .param("ids", reservationIds)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getLong("reservation_id"), k -> new java.util.ArrayList<>())
                            .add(new ReservationView.SeatLine(rs.getString("row_label"), rs.getInt("seat_number"),
                                    rs.getString("grade_code"), rs.getInt("price")));
                    return null;
                })
                .list();
        return out;
    }

    @Override
    public void markCancelled(long id, Instant cancelledAt) {
        jdbc.sql("""
                        UPDATE reservations SET status = 'CANCELLED', cancelled_at = :at
                         WHERE id = :id AND status = 'CONFIRMED'
                        """)
                .param("at", Timestamp.from(cancelledAt))
                .param("id", id)
                .update();
    }

    private static ReservationRow map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        return new ReservationRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getLong("performance_id"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getLong("show_id"),
                rs.getString("title"),
                rs.getString("venue"),
                rs.getInt("total_price"),
                rs.getString("name"),
                rs.getString("email_hash"),
                rs.getString("email_masked"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }
}
