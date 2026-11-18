package com.ticketing.show;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 모든 쿼리는 고정 SQL + 이름 있는 매개변수 바인딩만 사용한다(검색어 LIKE도 바인딩). */
@Repository
public class JdbcShowRepository implements ShowRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<Integer>> INTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcShowRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<ShowRow> findAll(Genre genre, String likePattern) {
        return jdbc.sql("""
                        SELECT * FROM shows
                         WHERE (:genre IS NULL OR genre = :genre)
                           AND (:pattern IS NULL
                                OR title LIKE :pattern ESCAPE '\\\\'
                                OR venue LIKE :pattern ESCAPE '\\\\')
                         ORDER BY id
                        """)
                .param("genre", genre == null ? null : genre.name())
                .param("pattern", likePattern)
                .query(this::mapShow)
                .list();
    }

    @Override
    public Optional<ShowRow> findById(long id) {
        return jdbc.sql("SELECT * FROM shows WHERE id = :id").param("id", id).query(this::mapShow).optional();
    }

    @Override
    public List<GradeRow> findGrades(Collection<Long> showIds) {
        if (showIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT show_id, code, name, price, sort_order FROM grades
                         WHERE show_id IN (:ids) ORDER BY show_id, sort_order
                        """)
                .param("ids", showIds)
                .query((rs, n) -> new GradeRow(rs.getLong("show_id"), rs.getString("code"), rs.getString("name"),
                        rs.getInt("price"), rs.getInt("sort_order")))
                .list();
    }

    @Override
    public List<PerformanceGradeStat> findPerformanceStats(Collection<Long> showIds) {
        if (showIds != null && showIds.isEmpty()) {
            return List.of();
        }
        String sql = showIds == null
                ? """
                  SELECT p.id, p.show_id, p.starts_at, s.grade_code,
                         COUNT(*) AS total, SUM(s.status = 'SOLD') AS sold
                    FROM performances p JOIN seats s ON s.performance_id = p.id
                   GROUP BY p.id, p.show_id, p.starts_at, s.grade_code
                  """
                : """
                  SELECT p.id, p.show_id, p.starts_at, s.grade_code,
                         COUNT(*) AS total, SUM(s.status = 'SOLD') AS sold
                    FROM performances p JOIN seats s ON s.performance_id = p.id
                   WHERE p.show_id IN (:ids)
                   GROUP BY p.id, p.show_id, p.starts_at, s.grade_code
                  """;
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (showIds != null) {
            spec = spec.param("ids", showIds);
        }
        return spec.query((rs, n) -> new PerformanceGradeStat(rs.getLong("id"), rs.getLong("show_id"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getString("grade_code"),
                        rs.getInt("total"), rs.getInt("sold")))
                .list();
    }

    @Override
    public Optional<PerformanceInfo> findPerformance(long performanceId) {
        return jdbc.sql("""
                        SELECT p.id, p.show_id, p.starts_at, s.title, s.venue, s.sale_opens_at,
                               s.seat_rows, s.seats_per_row, s.aisle_after
                          FROM performances p JOIN shows s ON s.id = p.show_id
                         WHERE p.id = :id
                        """)
                .param("id", performanceId)
                .query((rs, n) -> new PerformanceInfo(rs.getLong("id"), rs.getLong("show_id"), rs.getString("title"),
                        rs.getString("venue"), rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("sale_opens_at").toInstant(), rs.getInt("seat_rows"),
                        rs.getInt("seats_per_row"), read(rs.getString("aisle_after"), INTS)))
                .optional();
    }

    private ShowRow mapShow(ResultSet rs, int rowNum) throws SQLException {
        return new ShowRow(
                rs.getLong("id"),
                rs.getString("title"),
                Genre.valueOf(rs.getString("genre")),
                rs.getString("venue"),
                rs.getTimestamp("sale_opens_at").toInstant(),
                rs.getString("age_rating"),
                rs.getInt("running_minutes"),
                read(rs.getString("cast_members"), STRINGS),
                rs.getString("description"),
                read(rs.getString("notices"), STRINGS),
                rs.getInt("poster_seed"),
                rs.getInt("poster_hue"),
                rs.getInt("seat_rows"),
                rs.getInt("seats_per_row"),
                read(rs.getString("aisle_after"), INTS));
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 컬럼을 읽을 수 없습니다.", e);
        }
    }
}
