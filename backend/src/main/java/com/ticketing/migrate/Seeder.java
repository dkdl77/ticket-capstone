package com.ticketing.migrate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import com.ticketing.TicketingApplication;
import com.ticketing.auth.AuthService;
import com.ticketing.common.Transactions;
import com.ticketing.reservation.EmailPrivacy;
import com.ticketing.reservation.ReservationCodeGenerator;
import com.ticketing.seat.SeatService;
import com.ticketing.show.Genre;

/**
 * 시드 데이터(ARCHITECTURE.md 4.4). 공연명·출연진·장소는 모두 지어낸 것이다.
 * 실행 시각 기준 상대 날짜(KST)라 언제 돌려도 판매 중·오픈 예정·매진·종료가 섞인다.
 * <p>재실행 안전:
 * <ul>
 *   <li>공연은 slug, 회차는 (공연, 회차 번호)로 upsert → 중복 없이 날짜만 현재 기준으로 갱신</li>
 *   <li>좌석과 "미리 판매된 좌석"은 회차에 좌석이 하나도 없을 때(처음 만들 때)만 넣는다 → 실제 예매는 보존</li>
 * </ul>
 */
@Component
@Profile(TicketingApplication.MIGRATE_PROFILE)
public class Seeder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SEED_EMAIL = "seed-system@ticketing.invalid";
    private static final String SEED_NAME = "시드 예매";

    record SeedGrade(String code, String name, int price, int rows) {
    }

    record SeedPerf(int day, LocalTime time, double soldRatio) {
    }

    record SeedShow(String slug, String title, Genre genre, String venue, int saleOpensDay, LocalTime saleOpensTime,
                    String ageRating, int runningMinutes, List<String> cast, String description,
                    VenueLayout layout, List<SeedGrade> grades,
                    List<SeedPerf> performances, int hue) {
    }

    private static final List<String> COMMON_NOTICES = List.of(
            "1인 1회 최대 4매까지 예매할 수 있습니다.",
            "좌석 선택 후 제한 시간 안에 예매를 완료하지 않으면 선택이 자동으로 해제됩니다.",
            "공연 시작 이후에는 예매를 취소할 수 없습니다.",
            "공연 시작 후에는 입장이 제한될 수 있습니다.");

    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }

    static final List<SeedShow> SHOWS = List.of(
            // ---- CONCERT ----
            new SeedShow("dawnlight-blue-frequency", "새벽빛 밴드 단독 콘서트 〈푸른 주파수〉", Genre.CONCERT, "누리 아트홀",
                    -10, t(20, 0), "8세 이상", 150, List.of("새벽빛 밴드"),
                    "데뷔 5주년을 맞은 새벽빛 밴드의 단독 공연.\n새 앨범 수록곡과 대표곡을 밴드 편곡으로 들려줍니다.",
                    VenueLayout.FAN,
                    List.of(new SeedGrade("VIP", "VIP석", 154_000, 2), new SeedGrade("R", "R석", 132_000, 4),
                            new SeedGrade("S", "S석", 99_000, 0)),
                    List.of(new SeedPerf(3, t(19, 30), 1.0), new SeedPerf(4, t(18, 0), 0.6),
                            new SeedPerf(5, t(17, 0), 0.35)), 210),
            new SeedShow("citypop-summer-night", "한여름 시티팝 나이트", Genre.CONCERT, "별무리 라이브홀",
                    -5, t(14, 0), "만 7세 이상", 120, List.of("솔바람 트리오", "모래시계 세션"),
                    "도시의 밤을 닮은 시티팝 무대.\n두 팀이 번갈아 무대에 오르고 마지막 곡은 함께 부릅니다.",
                    VenueLayout.SMALL,
                    List.of(new SeedGrade("R", "R석", 110_000, 3), new SeedGrade("S", "S석", 88_000, 0)),
                    List.of(new SeedPerf(9, t(19, 0), 0.2), new SeedPerf(10, t(19, 0), 0.1)), 30),
            new SeedShow("mulgyeol-indie-fest-2026", "물결 인디 페스티벌 2026", Genre.CONCERT, "물결 야외극장",
                    -40, t(12, 0), "전체 관람가", 240, List.of("달무리 밴드", "종이비행기", "늦은 오후"),
                    "강가 야외무대에서 열리는 이틀간의 인디 음악 축제.",
                    VenueLayout.MEDIUM,
                    List.of(new SeedGrade("R", "R석", 99_000, 5), new SeedGrade("S", "S석", 77_000, 0)),
                    List.of(new SeedPerf(-3, t(15, 0), 0.7), new SeedPerf(-2, t(15, 0), 0.5)), 140),
            // ---- MUSICAL ----
            new SeedShow("girl-who-folds-time", "뮤지컬 〈시간을 접는 소녀〉", Genre.MUSICAL, "온새미로 극장",
                    -14, t(14, 0), "8세 이상", 160, List.of("한소담", "도경민", "서하린", "류시온"),
                    "접힌 종이 한 장으로 하루를 되돌리는 소녀의 이야기.\n2막 구성, 인터미션 20분.",
                    VenueLayout.MEDIUM,
                    List.of(new SeedGrade("VIP", "VIP석", 160_000, 3), new SeedGrade("R", "R석", 140_000, 4),
                            new SeedGrade("S", "S석", 110_000, 0)),
                    List.of(new SeedPerf(6, t(19, 30), 0.45), new SeedPerf(7, t(14, 0), 0.55),
                            new SeedPerf(7, t(19, 0), 0.3), new SeedPerf(8, t(15, 0), 0.25)), 300),
            new SeedShow("lighthouse-keeper-song", "뮤지컬 〈등대지기의 노래〉", Genre.MUSICAL, "다온 아트센터 대극장",
                    -7, t(14, 0), "만 7세 이상", 150, List.of("윤재하", "백소담", "권도율"),
                    "외딴섬 등대를 지키는 사람들의 사계절을 노래로 엮은 창작 뮤지컬.",
                    VenueLayout.CUT_CORNERS,
                    List.of(new SeedGrade("VIP", "VIP석", 150_000, 2), new SeedGrade("R", "R석", 130_000, 4),
                            new SeedGrade("S", "S석", 90_000, 0)),
                    List.of(new SeedPerf(12, t(19, 30), 0.15), new SeedPerf(13, t(14, 0), 0.1),
                            new SeedPerf(13, t(19, 0), 0.05)), 190),
            new SeedShow("cloud-station", "뮤지컬 〈구름 정거장〉", Genre.MUSICAL, "별빛 씨어터",
                    2, t(14, 0), "전체 관람가", 100, List.of("나은솔", "차민결"),
                    "구름 위 작은 정거장에서 길 잃은 기차를 기다리는 가족 뮤지컬.",
                    VenueLayout.FAN,
                    List.of(new SeedGrade("VIP", "VIP석", 120_000, 2), new SeedGrade("R", "R석", 100_000, 3),
                            new SeedGrade("S", "S석", 70_000, 0)),
                    List.of(new SeedPerf(20, t(19, 30), 0), new SeedPerf(21, t(14, 0), 0)), 260),
            // ---- PLAY ----
            new SeedShow("last-letter-thursday", "연극 〈마지막 편지는 목요일에〉", Genre.PLAY, "소금꽃 소극장",
                    -12, t(14, 0), "13세 이상", 100, List.of("임하늘", "정새온"),
                    "매주 목요일 도착하는 편지와, 그 편지를 기다리는 두 사람의 이야기.",
                    VenueLayout.SMALL,
                    List.of(new SeedGrade("R", "R석", 55_000, 3), new SeedGrade("S", "S석", 45_000, 0)),
                    List.of(new SeedPerf(2, t(20, 0), 1.0)), 15),
            new SeedShow("rooftop-observatory", "연극 〈옥탑방 천문대〉", Genre.PLAY, "나래 소극장",
                    -6, t(14, 0), "13세 이상", 110, List.of("조윤새", "남궁현", "오세린"),
                    "옥탑방에 망원경을 들여놓은 세 친구의 좌충우돌 코미디.",
                    VenueLayout.SMALL,
                    List.of(new SeedGrade("R", "R석", 50_000, 4), new SeedGrade("S", "S석", 40_000, 0)),
                    List.of(new SeedPerf(4, t(20, 0), 0.4), new SeedPerf(5, t(16, 0), 0.2),
                            new SeedPerf(5, t(19, 30), 0.3)), 50),
            new SeedShow("second-move", "연극 〈두 번째 이사〉", Genre.PLAY, "모퉁이 극장",
                    12, t(14, 0), "15세 이상", 95, List.of("배다온", "모하담"),
                    "같은 집으로 두 번 이사 온 사람과, 그 집에 남아 있던 기억들.",
                    VenueLayout.CUT_CORNERS,
                    List.of(new SeedGrade("R", "R석", 60_000, 3), new SeedGrade("S", "S석", 45_000, 0)),
                    List.of(new SeedPerf(30, t(19, 30), 0)), 95),
            // ---- CLASSIC ----
            new SeedShow("autumn-chamber-night", "가을 실내악의 밤", Genre.CLASSIC, "하늘결 체임버홀",
                    -8, t(10, 0), "8세 이상", 110, List.of("하늘결 현악 사중주"),
                    "현악 사중주로 듣는 가을 밤의 실내악 프로그램.",
                    VenueLayout.FAN,
                    List.of(new SeedGrade("R", "R석", 80_000, 3), new SeedGrade("S", "S석", 60_000, 4),
                            new SeedGrade("A", "A석", 40_000, 0)),
                    List.of(new SeedPerf(10, t(19, 30), 0.3)), 25),
            new SeedShow("mirinae-youth-orchestra", "미리내 청년 오케스트라 정기 연주회", Genre.CLASSIC, "미리내 콘서트홀",
                    6, t(10, 0), "8세 이상", 120, List.of("미리내 청년 오케스트라", "지휘 한소율"),
                    "청년 연주자 80명이 들려주는 교향곡의 밤.",
                    VenueLayout.MEDIUM,
                    List.of(new SeedGrade("R", "R석", 70_000, 4), new SeedGrade("S", "S석", 50_000, 4),
                            new SeedGrade("A", "A석", 30_000, 0)),
                    List.of(new SeedPerf(25, t(19, 30), 0), new SeedPerf(26, t(17, 0), 0)), 220),
            new SeedShow("piano-memory-of-water", "피아노 리사이틀 〈물의 기억〉", Genre.CLASSIC, "하늘결 체임버홀",
                    -3, t(10, 0), "8세 이상", 90, List.of("피아니스트 서윤슬"),
                    "물을 주제로 고른 소품들로 꾸민 피아노 독주회.",
                    VenueLayout.FAN,
                    List.of(new SeedGrade("R", "R석", 70_000, 4), new SeedGrade("S", "S석", 50_000, 0)),
                    List.of(new SeedPerf(15, t(19, 30), 0.12)), 175));

    public record Result(int shows, int performances, int newSeats, int newSeedReservations, boolean demoAccount) {
    }

    /** V2 마이그레이션이 만든 로그인 불가 시드 계정. 미리 판매된 좌석은 이 계정 소유다. */
    static final String SEED_ACCOUNT_EMAIL = "seed-system@ticketing.invalid";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Transactions tx;
    private final ObjectMapper json;
    private final AuthService auth;
    private final String demoEmail;
    private final String demoPassword;
    private final String demoName;
    private long seedUserId;

    public Seeder(JdbcClient jdbc, NamedParameterJdbcTemplate named, Transactions tx, ObjectMapper json,
            AuthService auth,
            @Value("${app.seed.demo-email:}") String demoEmail,
            @Value("${app.seed.demo-password:}") String demoPassword,
            @Value("${app.seed.demo-name:시연 사용자}") String demoName) {
        this.jdbc = jdbc;
        this.named = named;
        this.tx = tx;
        this.json = json;
        this.auth = auth;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
        this.demoName = demoName;
    }

    public Result seed(Instant now) {
        seedUserId = jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", SEED_ACCOUNT_EMAIL)
                .query(Long.class).single();
        // 시연 계정: SEED_DEMO_EMAIL·SEED_DEMO_PASSWORD 둘 다 있을 때만. 비밀번호는 로그에 남기지 않는다.
        boolean demo = !demoEmail.isBlank() && !demoPassword.isBlank();
        if (demo) {
            auth.upsertDemoAccount(demoEmail, demoPassword, demoName);
        }
        LocalDate today = now.atZone(KST).toLocalDate();
        int performances = 0;
        int newSeats = 0;
        int newReservations = 0;
        for (int i = 0; i < SHOWS.size(); i++) {
            SeedShow show = SHOWS.get(i);
            int showIndex = i;
            int[] counts = tx.inTransaction(() -> seedShow(showIndex, show, today));
            performances += show.performances().size();
            newSeats += counts[0];
            newReservations += counts[1];
        }
        return new Result(SHOWS.size(), performances, newSeats, newReservations, demo);
    }

    private int[] seedShow(int showIndex, SeedShow s, LocalDate today) {
        Instant saleOpensAt = at(today, s.saleOpensDay(), s.saleOpensTime());
        jdbc.sql("""
                        INSERT INTO shows (slug, title, genre, venue, sale_opens_at, age_rating, running_minutes,
                                           cast_members, description, notices, poster_seed, poster_hue,
                                           seat_rows, seats_per_row, aisle_after)
                        VALUES (:slug, :title, :genre, :venue, :opens, :age, :running,
                                :cast, :description, :notices, :seed, :hue, :rows, :perRow, :aisle) AS new
                        ON DUPLICATE KEY UPDATE
                            title = new.title, genre = new.genre, venue = new.venue, sale_opens_at = new.sale_opens_at,
                            age_rating = new.age_rating, running_minutes = new.running_minutes,
                            cast_members = new.cast_members, description = new.description, notices = new.notices,
                            poster_seed = new.poster_seed, poster_hue = new.poster_hue
                        """)
                .param("slug", s.slug()).param("title", s.title()).param("genre", s.genre().name())
                .param("venue", s.venue()).param("opens", Timestamp.from(saleOpensAt)).param("age", s.ageRating())
                .param("running", s.runningMinutes()).param("cast", toJson(s.cast()))
                .param("description", s.description()).param("notices", toJson(COMMON_NOTICES))
                .param("seed", 1000 + showIndex * 37).param("hue", s.hue())
                .param("rows", s.layout().rows()).param("perRow", s.layout().seatsPerRow())
                .param("aisle", toJson(s.layout().aisleAfter()))
                .update();
        long showId = jdbc.sql("SELECT id FROM shows WHERE slug = :slug").param("slug", s.slug())
                .query(Long.class).single();

        for (int g = 0; g < s.grades().size(); g++) {
            SeedGrade grade = s.grades().get(g);
            jdbc.sql("""
                            INSERT INTO grades (show_id, code, name, price, sort_order) VALUES (:show, :code, :name, :price, :ord) AS new
                            ON DUPLICATE KEY UPDATE name = new.name, price = new.price, sort_order = new.sort_order
                            """)
                    .param("show", showId).param("code", grade.code()).param("name", grade.name())
                    .param("price", grade.price()).param("ord", g).update();
        }

        int newSeats = 0;
        int newReservations = 0;
        for (int p = 0; p < s.performances().size(); p++) {
            SeedPerf perf = s.performances().get(p);
            jdbc.sql("""
                            INSERT INTO performances (show_id, seq, starts_at) VALUES (:show, :seq, :starts) AS new
                            ON DUPLICATE KEY UPDATE starts_at = new.starts_at
                            """)
                    .param("show", showId).param("seq", p + 1)
                    .param("starts", Timestamp.from(at(today, perf.day(), perf.time()))).update();
            long perfId = jdbc.sql("SELECT id FROM performances WHERE show_id = :show AND seq = :seq")
                    .param("show", showId).param("seq", p + 1).query(Long.class).single();

            int existing = jdbc.sql("SELECT COUNT(*) FROM seats WHERE performance_id = :p").param("p", perfId)
                    .query(Integer.class).single();
            if (existing > 0) {
                continue; // 이미 만든 회차: 좌석·판매 상태(실제 예매 포함)를 건드리지 않는다
            }
            newSeats += insertSeats(perfId, s);
            newReservations += preSell(showIndex, p, perfId, showId, perf.soldRatio(), Instant.now());
        }
        return new int[] {newSeats, newReservations};
    }

    /**
     * 공연장 템플릿의 격자를 돌며 실제 좌석이 있는 칸만 만든다. 좌석 번호 = 격자의 열 위치(빈칸이 있으면 번호가 건너뜀).
     * 등급은 앞쪽 행부터 높은 등급. rows=0인 마지막 등급이 나머지 행을 모두 가진다.
     */
    private int insertSeats(long perfId, SeedShow s) {
        VenueLayout layout = s.layout();
        List<MapSqlParameterSource> batch = new ArrayList<>();
        int row = 0;
        for (SeedGrade grade : s.grades()) {
            int rowsForGrade = grade.rows() == 0 ? layout.rows() - row : grade.rows();
            for (int r = 0; r < rowsForGrade && row < layout.rows(); r++, row++) {
                for (int n = 1; n <= layout.seatsPerRow(); n++) {
                    if (!layout.exists(row, n)) {
                        continue; // 자리가 없는 칸
                    }
                    batch.add(new MapSqlParameterSource().addValue("p", perfId)
                            .addValue("row", SeatService.rowLabel(row)).addValue("n", n).addValue("g", grade.code()));
                }
            }
        }
        named.batchUpdate("""
                INSERT INTO seats (performance_id, row_label, seat_number, grade_code, status)
                VALUES (:p, :row, :n, :g, 'AVAILABLE')
                """, batch.toArray(MapSqlParameterSource[]::new));
        return batch.size();
    }

    /** 랭킹·매진 상태가 비지 않도록 일부 좌석을 시드 예매(최대 4석씩)로 판매 처리. 결정적 난수라 매번 같은 좌석. */
    private int preSell(int showIndex, int perfIndex, long perfId, long showId, double ratio, Instant now) {
        if (ratio <= 0) {
            return 0;
        }
        List<long[]> seats = jdbc.sql("""
                        SELECT s.id, g.price FROM seats s
                          JOIN grades g ON g.show_id = :show AND g.code = s.grade_code
                         WHERE s.performance_id = :p ORDER BY s.id
                        """)
                .param("show", showId).param("p", perfId)
                .query((rs, n) -> new long[] {rs.getLong("id"), rs.getLong("price")}).list();
        List<long[]> shuffled = new ArrayList<>(seats);
        Collections.shuffle(shuffled, new Random(showIndex * 100L + perfIndex));
        int toSell = (int) Math.round(seats.size() * Math.min(1.0, ratio));
        List<long[]> sold = shuffled.subList(0, toSell);

        int reservationsMade = 0;
        for (int i = 0; i < sold.size(); i += 4) {
            List<long[]> group = sold.subList(i, Math.min(i + 4, sold.size()));
            String code = seedCode(showIndex, perfIndex, reservationsMade);
            int total = group.stream().mapToInt(x -> (int) x[1]).sum();
            jdbc.sql("""
                            INSERT INTO reservations (code, performance_id, user_id, total_price, name, email_hash, email_masked,
                                                      status, created_at)
                            VALUES (:code, :p, :uid, :total, :name, :hash, :masked, 'CONFIRMED', :at)
                            """)
                    .param("code", code).param("p", perfId).param("uid", seedUserId).param("total", total).param("name", SEED_NAME)
                    .param("hash", EmailPrivacy.hash(SEED_EMAIL)).param("masked", EmailPrivacy.mask(SEED_EMAIL))
                    .param("at", Timestamp.from(now)).update();
            long rid = jdbc.sql("SELECT id FROM reservations WHERE code = :code").param("code", code)
                    .query(Long.class).single();
            List<Long> ids = group.stream().map(x -> x[0]).toList();
            jdbc.sql("UPDATE seats SET status = 'SOLD', reservation_id = :r WHERE id IN (:ids)")
                    .param("r", rid).param("ids", ids).update();
            named.batchUpdate("INSERT INTO reservation_seats (reservation_id, seat_id, price) VALUES (:r, :s, :price)",
                    group.stream().map(x -> new MapSqlParameterSource().addValue("r", rid).addValue("s", x[0])
                            .addValue("price", x[1])).toArray(MapSqlParameterSource[]::new));
            reservationsMade++;
        }
        return reservationsMade;
    }

    private static Instant at(LocalDate today, int dayOffset, LocalTime time) {
        return today.plusDays(dayOffset).atTime(time).atZone(KST).toInstant();
    }

    /** "SEED" + 8자리(예매번호 알파벳). 무작위 예매번호와 같은 형식이다. */
    static String seedCode(int showIndex, int perfIndex, int seq) {
        String alphabet = ReservationCodeGenerator.ALPHABET;
        long n = showIndex * 100_000L + perfIndex * 1_000L + seq;
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = alphabet.charAt((int) (n % alphabet.length()));
            n /= alphabet.length();
        }
        return "SEED" + new String(out);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
