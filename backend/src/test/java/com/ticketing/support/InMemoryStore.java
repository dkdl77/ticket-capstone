package com.ticketing.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import com.ticketing.auth.LoginAttemptStore;
import com.ticketing.auth.UserRepository;
import com.ticketing.common.Transactions;
import com.ticketing.config.AppProperties;
import com.ticketing.queue.QueueStore;
import com.ticketing.reservation.IdempotencyStore;
import com.ticketing.reservation.NewReservation;
import com.ticketing.reservation.ReservationRepository;
import com.ticketing.reservation.ReservationRow;
import com.ticketing.reservation.ReservationStatus;
import com.ticketing.reservation.ReservationView;
import com.ticketing.seat.HoldStore;
import com.ticketing.seat.SeatRepository;
import com.ticketing.seat.SeatService;
import com.ticketing.show.Genre;
import com.ticketing.show.GradeRow;
import com.ticketing.show.PerformanceGradeStat;
import com.ticketing.show.PerformanceInfo;
import com.ticketing.show.ShowRepository;
import com.ticketing.show.ShowRow;

/**
 * 서비스 단위 테스트용 가짜 저장소 모음(MySQL·Redis 없이 업무 규칙만 검증).
 * 대기열·선점 가짜 구현은 Lua 스크립트와 같은 규칙을 흉내 낸다. 실제 Lua 동작은 통합 테스트(*IT)가 실제 Redis로 검증한다.
 */
public final class InMemoryStore {

    public final MutableClock clock;
    public final Map<Long, ShowRow> shows = new LinkedHashMap<>();
    public final List<GradeRow> grades = new ArrayList<>();
    public final Map<Long, long[]> performances = new LinkedHashMap<>(); // id → {showId, startsAtMillis}
    public final Map<Long, Seat> seats = new LinkedHashMap<>();
    public final Map<Long, ReservationRow> reservations = new LinkedHashMap<>();
    public final Map<Long, List<long[]>> reservationSeats = new HashMap<>(); // rid → [{seatId, price}]
    private final AtomicLong ids = new AtomicLong(100);

    public InMemoryStore(Instant now) {
        this.clock = new MutableClock(now);
    }

    public static final class Seat {
        public final long id;
        public final long performanceId;
        public final String row;
        public final int number;
        public final String grade;
        public SeatRepository.Status status = SeatRepository.Status.AVAILABLE;
        public Long reservationId;

        Seat(long id, long performanceId, String row, int number, String grade) {
            this.id = id;
            this.performanceId = performanceId;
            this.row = row;
            this.number = number;
            this.grade = grade;
        }
    }

    // ---------- 테스트 데이터 만들기 ----------

    public long addShow(String title, Genre genre, Instant saleOpensAt) {
        long id = ids.incrementAndGet();
        shows.put(id, new ShowRow(id, title, genre, "테스트홀", saleOpensAt, "전체 관람가", 100, List.of("출연"),
                "설명", List.of("유의"), 1, 10, 2, 4, List.of(2)));
        grades.add(new GradeRow(id, "R", "R석", 100_000, 0));
        grades.add(new GradeRow(id, "S", "S석", 50_000, 1));
        return id;
    }

    /** 2행 × 4석: A행 R석, B행 S석. */
    public long addPerformance(long showId, Instant startsAt) {
        long id = ids.incrementAndGet();
        performances.put(id, new long[] {showId, startsAt.toEpochMilli()});
        for (int r = 0; r < 2; r++) {
            for (int n = 1; n <= 4; n++) {
                long sid = ids.incrementAndGet();
                seats.put(sid, new Seat(sid, id, SeatService.rowLabel(r), n, r == 0 ? "R" : "S"));
            }
        }
        return id;
    }

    public List<Long> seatIds(long performanceId) {
        return seats.values().stream().filter(s -> s.performanceId == performanceId).map(s -> s.id).toList();
    }

    public void sell(long seatId) {
        Seat s = seats.get(seatId);
        s.status = SeatRepository.Status.SOLD;
        s.reservationId = -1L;
    }

    private int price(long showId, String grade) {
        return grades.stream().filter(g -> g.showId() == showId && g.code().equals(grade)).findFirst().orElseThrow().price();
    }

    // ---------- ShowRepository ----------

    public final class Shows implements ShowRepository {
        @Override
        public List<ShowRow> findAll(Genre genre, String likePattern) {
            String needle = likePattern == null ? null
                    : likePattern.substring(1, likePattern.length() - 1).replace("\\%", "%").replace("\\_", "_")
                    .replace("\\\\", "\\");
            return shows.values().stream()
                    .filter(s -> genre == null || s.genre() == genre)
                    .filter(s -> needle == null || s.title().contains(needle) || s.venue().contains(needle))
                    .toList();
        }

        @Override
        public Optional<ShowRow> findById(long id) {
            return Optional.ofNullable(shows.get(id));
        }

        @Override
        public List<GradeRow> findGrades(Collection<Long> showIds) {
            return grades.stream().filter(g -> showIds.contains(g.showId()))
                    .sorted(Comparator.comparingLong(GradeRow::showId).thenComparingInt(GradeRow::sortOrder)).toList();
        }

        @Override
        public List<PerformanceGradeStat> findPerformanceStats(Collection<Long> showIds) {
            Map<String, int[]> agg = new LinkedHashMap<>();
            for (Seat s : seats.values()) {
                long showId = performances.get(s.performanceId)[0];
                if (showIds != null && !showIds.contains(showId)) {
                    continue;
                }
                int[] a = agg.computeIfAbsent(s.performanceId + "|" + s.grade, k -> new int[2]);
                a[0]++;
                if (s.status == SeatRepository.Status.SOLD) {
                    a[1]++;
                }
            }
            List<PerformanceGradeStat> out = new ArrayList<>();
            agg.forEach((k, v) -> {
                long perf = Long.parseLong(k.split("\\|")[0]);
                long[] p = performances.get(perf);
                out.add(new PerformanceGradeStat(perf, p[0], Instant.ofEpochMilli(p[1]), k.split("\\|")[1], v[0], v[1]));
            });
            return out;
        }

        @Override
        public Optional<PerformanceInfo> findPerformance(long performanceId) {
            long[] p = performances.get(performanceId);
            if (p == null) {
                return Optional.empty();
            }
            ShowRow s = shows.get(p[0]);
            return Optional.of(new PerformanceInfo(performanceId, s.id(), s.title(), s.venue(),
                    Instant.ofEpochMilli(p[1]), s.saleOpensAt(), s.seatRows(), s.seatsPerRow(), s.aisleAfter()));
        }
    }

    // ---------- SeatRepository ----------

    public final class Seats implements SeatRepository {
        @Override
        public List<SeatRow> findByPerformance(long performanceId) {
            return seats.values().stream().filter(s -> s.performanceId == performanceId)
                    .map(s -> new SeatRow(s.id, s.row, s.number, s.grade, s.status)).toList();
        }

        @Override
        public List<PricedSeat> findPriced(long performanceId, Collection<Long> seatIds) {
            long showId = performances.get(performanceId)[0];
            return seats.values().stream().filter(s -> s.performanceId == performanceId && seatIds.contains(s.id))
                    .map(s -> new PricedSeat(s.id, s.row, s.number, s.grade, price(showId, s.grade), s.status)).toList();
        }

        @Override
        public int countAvailable(long performanceId) {
            return (int) seats.values().stream()
                    .filter(s -> s.performanceId == performanceId && s.status == Status.AVAILABLE).count();
        }

        @Override
        public int markSold(long performanceId, Collection<Long> seatIds, long reservationId) {
            int n = 0;
            for (Long id : seatIds) {
                Seat s = seats.get(id);
                if (s != null && s.performanceId == performanceId && s.status == Status.AVAILABLE) {
                    s.status = Status.SOLD;
                    s.reservationId = reservationId;
                    n++;
                }
            }
            return n;
        }

        @Override
        public int releaseByReservation(long reservationId) {
            int n = 0;
            for (Seat s : seats.values()) {
                if (s.reservationId != null && s.reservationId == reservationId) {
                    s.status = Status.AVAILABLE;
                    s.reservationId = null;
                    n++;
                }
            }
            return n;
        }
    }

    // ---------- ReservationRepository ----------

    public final class Reservations implements ReservationRepository {
        public boolean failNextInsertSeats;
        public final Map<Long, Long> owner = new HashMap<>(); // reservationId → userId

        @Override
        public long insert(NewReservation r) {
            if (reservations.values().stream().anyMatch(x -> x.code().equals(r.code()))) {
                throw new DuplicateKeyException("dup");
            }
            long id = ids.incrementAndGet();
            long[] p = performances.get(r.performanceId());
            ShowRow s = shows.get(p[0]);
            reservations.put(id, new ReservationRow(id, r.code(), r.performanceId(), Instant.ofEpochMilli(p[1]), s.id(),
                    s.title(), s.venue(), r.totalPrice(), r.name(), r.emailHash(), r.emailMasked(),
                    ReservationStatus.CONFIRMED, r.createdAt(), null));
            owner.put(id, r.userId());
            return id;
        }

        @Override
        public void insertSeats(long reservationId, List<SeatRepository.PricedSeat> list) {
            if (failNextInsertSeats) {
                failNextInsertSeats = false;
                throw new DataAccessResourceFailureException("DB 다운(테스트)");
            }
            reservationSeats.put(reservationId, list.stream().map(s -> new long[] {s.id(), s.price()}).toList());
        }

        @Override
        public int lockUserAndCountConfirmedSeats(long userId, long performanceId) {
            return reservations.values().stream()
                    .filter(r -> owner.get(r.id()) == userId && r.performanceId() == performanceId
                            && r.status() == ReservationStatus.CONFIRMED)
                    .mapToInt(r -> reservationSeats.getOrDefault(r.id(), List.of()).size())
                    .sum();
        }

        @Override
        public List<ReservationRow> findByUser(long userId) {
            List<ReservationRow> out = new ArrayList<>(reservations.values().stream()
                    .filter(r -> owner.get(r.id()) == userId).toList());
            out.sort(Comparator.comparing(ReservationRow::createdAt).thenComparing(ReservationRow::id).reversed());
            return out;
        }

        @Override
        public Optional<ReservationRow> findByCodeAndUserForUpdate(String code, long userId) {
            return reservations.values().stream()
                    .filter(r -> r.code().equals(code) && owner.get(r.id()) == userId).findFirst();
        }

        @Override
        public Map<Long, List<ReservationView.SeatLine>> findSeats(Collection<Long> reservationIds) {
            Map<Long, List<ReservationView.SeatLine>> out = new HashMap<>();
            for (Long rid : reservationIds) {
                out.put(rid, reservationSeats.getOrDefault(rid, List.of()).stream().map(x -> {
                    Seat s = seats.get(x[0]);
                    return new ReservationView.SeatLine(s.row, s.number, s.grade, (int) x[1]);
                }).toList());
            }
            return out;
        }

        @Override
        public void markCancelled(long id, Instant at) {
            reservations.computeIfPresent(id, (k, r) -> r.status() == ReservationStatus.CONFIRMED ? r.cancelled(at) : r);
        }
    }

    // ---------- UserRepository / LoginAttemptStore ----------

    public final class Users implements UserRepository {
        public final Map<Long, UserRow> rows = new LinkedHashMap<>();

        @Override
        public synchronized long insert(String email, String passwordHash, String name) {
            if (rows.values().stream().anyMatch(u -> u.email().equals(email))) {
                throw new DuplicateKeyException("dup email");
            }
            long id = ids.incrementAndGet();
            rows.put(id, new UserRow(id, email, name, passwordHash, true));
            return id;
        }

        @Override
        public Optional<UserRow> findByEmail(String normalizedEmail) {
            return rows.values().stream().filter(u -> u.email().equals(normalizedEmail)).findFirst();
        }

        @Override
        public Optional<UserRow> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void upsertDemo(String email, String passwordHash, String name) {
            Optional<UserRow> existing = findByEmail(email);
            long id = existing.map(UserRow::id).orElseGet(ids::incrementAndGet);
            rows.put(id, new UserRow(id, email, name, passwordHash, true));
        }

        /** 로그인 불가 계정(시드 계정 흉내). */
        public long addDisabled(String email) {
            long id = ids.incrementAndGet();
            rows.put(id, new UserRow(id, email, "시드", "!", false));
            return id;
        }
    }

    public final class LoginAttempts implements LoginAttemptStore {
        private final Map<String, long[]> state = new HashMap<>(); // key → {count, expiresAtMillis}

        private long[] live(String key) {
            long[] v = state.get(key);
            if (v != null && v[1] <= clock.millis()) {
                state.remove(key);
                return null;
            }
            return v;
        }

        @Override
        public Optional<Duration> lockedFor(String accountKey, int maxFailures) {
            long[] v = live(accountKey);
            return v == null || v[0] < maxFailures ? Optional.empty() : Optional.of(Duration.ofMillis(v[1] - clock.millis()));
        }

        @Override
        public Optional<Duration> recordFailure(String accountKey, int maxFailures, Duration lockDuration) {
            long[] v = live(accountKey);
            long count = v == null ? 1 : v[0] + 1;
            state.put(accountKey, new long[] {count, clock.millis() + lockDuration.toMillis()});
            return count >= maxFailures ? Optional.of(lockDuration) : Optional.empty();
        }

        @Override
        public void reset(String accountKey) {
            state.remove(accountKey);
        }
    }

    // ---------- QueueStore (Lua 규칙 흉내) ----------

    public final class Queue implements QueueStore {
        private final Map<String, long[]> tokenOwner = new HashMap<>(); // tokenId → {perf, uid}
        private final Map<String, String> userToken = new HashMap<>();   // perf:uid → tokenId
        private final Map<Long, LinkedHashMap<String, Long>> waiting = new HashMap<>(); // token → lastSeen
        private final Map<Long, Map<String, Long>> active = new HashMap<>();             // token → until

        private void advance(long perf, AppProperties.Queue s) {
            long now = clock.millis();
            active.computeIfAbsent(perf, k -> new HashMap<>()).values().removeIf(until -> until <= now);
            waiting.computeIfAbsent(perf, k -> new LinkedHashMap<>()).values()
                    .removeIf(seen -> seen < now - s.waitTtl().toMillis());
            var it = waiting.get(perf).keySet().iterator();
            while (active.get(perf).size() < s.maxActive() && it.hasNext()) {
                String t = it.next();
                it.remove();
                active.get(perf).put(t, now + s.admit().toMillis());
            }
        }

        private Optional<TokenState> state(String tokenId) {
            long[] o = tokenOwner.get(tokenId);
            if (o == null) {
                return Optional.empty();
            }
            long perf = o[0];
            Long until = active.getOrDefault(perf, Map.of()).get(tokenId);
            if (until != null && until > clock.millis()) {
                return Optional.of(new TokenState(perf, o[1], State.ADMITTED, 0, Instant.ofEpochMilli(until)));
            }
            List<String> order = new ArrayList<>(waiting.getOrDefault(perf, new LinkedHashMap<>()).keySet());
            int pos = order.indexOf(tokenId);
            return pos < 0 ? Optional.empty() : Optional.of(new TokenState(perf, o[1], State.WAITING, pos, null));
        }

        @Override
        public TokenState enter(long performanceId, long userId, String tokenId, AppProperties.Queue s) {
            advance(performanceId, s);
            tokenOwner.put(tokenId, new long[] {performanceId, userId});
            userToken.put(performanceId + ":" + userId, tokenId);
            if (active.get(performanceId).size() < s.maxActive()) {
                active.get(performanceId).put(tokenId, clock.millis() + s.admit().toMillis());
            } else {
                waiting.get(performanceId).put(tokenId, clock.millis());
            }
            return state(tokenId).orElseThrow();
        }

        @Override
        public Optional<String> tokenOf(long performanceId, long userId) {
            return Optional.ofNullable(userToken.get(performanceId + ":" + userId));
        }

        @Override
        public Optional<TokenState> poll(String tokenId, AppProperties.Queue s) {
            long[] o = tokenOwner.get(tokenId);
            if (o == null) {
                return Optional.empty();
            }
            var w = waiting.computeIfAbsent(o[0], k -> new LinkedHashMap<>());
            if (w.containsKey(tokenId)) {
                if (w.get(tokenId) < clock.millis() - s.waitTtl().toMillis()) {
                    w.remove(tokenId);
                } else {
                    w.put(tokenId, clock.millis()); // put on existing key keeps insertion order
                }
            }
            advance(o[0], s);
            return state(tokenId);
        }

        @Override
        public Optional<TokenState> peek(String tokenId) {
            return state(tokenId);
        }

        @Override
        public void leave(String tokenId, AppProperties.Queue s) {
            long[] o = tokenOwner.remove(tokenId);
            if (o == null) {
                return;
            }
            userToken.remove(o[0] + ":" + o[1], tokenId);
            waiting.getOrDefault(o[0], new LinkedHashMap<>()).remove(tokenId);
            active.getOrDefault(o[0], new HashMap<>()).remove(tokenId);
            advance(o[0], s);
        }
    }

    // ---------- HoldStore (TTL 흉내) ----------

    public final class Holds implements HoldStore {
        public final Map<String, Hold> holds = new HashMap<>();
        public final Map<String, String> seatHolder = new HashMap<>(); // perf:seat → holdId
        private final Map<String, String> tokenHold = new HashMap<>();

        private boolean alive(Hold h) {
            return h != null && h.expiresAt().isAfter(clock.instant());
        }

        private String key(long perf, long seat) {
            return perf + ":" + seat;
        }

        @Override
        public Result create(String tokenId, long userId, long performanceId, List<Long> seatIds, int totalPrice,
                Duration ttl, Instant notAfter) {
            String prev = tokenHold.get(tokenId);
            List<Long> taken = new ArrayList<>();
            for (Long id : seatIds) {
                String holder = seatHolder.get(key(performanceId, id));
                if (holder != null && alive(holds.get(holder)) && !holder.equals(prev)) {
                    taken.add(id);
                }
            }
            if (!taken.isEmpty()) {
                return new Taken(taken);
            }
            if (prev != null) {
                release(prev, tokenId);
            }
            Instant expires = clock.instant().plus(ttl);
            if (notAfter != null && notAfter.isBefore(expires)) {
                expires = notAfter;
            }
            if (Duration.between(clock.instant(), expires).toMillis() < 1000) {
                return new AdmissionEnding();
            }
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
            Hold h = new Hold(id, tokenId, userId, performanceId, List.copyOf(seatIds), totalPrice, expires);
            holds.put(id, h);
            seatIds.forEach(s -> seatHolder.put(key(performanceId, s), id));
            tokenHold.put(tokenId, id);
            return new Created(h);
        }

        @Override
        public Optional<Hold> find(String holdId) {
            Hold h = holds.get(holdId);
            return alive(h) ? Optional.of(h) : Optional.empty();
        }

        @Override
        public Optional<Hold> currentOf(String tokenId) {
            String id = tokenHold.get(tokenId);
            return id == null ? Optional.empty() : find(id);
        }

        @Override
        public Map<Long, String> holdersOf(long performanceId, Collection<Long> seatIds) {
            Map<Long, String> out = new HashMap<>();
            for (Long id : seatIds) {
                String holder = seatHolder.get(key(performanceId, id));
                if (holder != null && alive(holds.get(holder))) {
                    out.put(id, holder);
                }
            }
            return out;
        }

        @Override
        public boolean release(String holdId, String tokenId) {
            Hold h = holds.get(holdId);
            if (!alive(h) || !h.tokenId().equals(tokenId)) {
                return false;
            }
            holds.remove(holdId);
            h.seatIds().forEach(s -> seatHolder.remove(key(h.performanceId(), s), holdId));
            tokenHold.remove(tokenId, holdId);
            return true;
        }

        @Override
        public void releaseCurrent(String tokenId) {
            String id = tokenHold.get(tokenId);
            if (id != null) {
                release(id, tokenId);
            }
        }
    }

    public static final class Idempotency implements IdempotencyStore {
        public final Map<String, Object> state = new HashMap<>();
        public static final Object PENDING = new Object();

        @Override
        public synchronized Claim claim(String key) {
            Object v = state.get(key);
            if (v == null) {
                state.put(key, PENDING);
                return new Acquired();
            }
            return v == PENDING ? new InProgress() : new Completed((ReservationView) v);
        }

        @Override
        public synchronized void complete(String key, ReservationView response) {
            state.put(key, response);
        }

        @Override
        public synchronized void release(String key) {
            state.remove(key);
        }
    }

    /** 예외가 나면 좌석·예매 상태를 트랜잭션 시작 전으로 되돌린다(롤백 흉내). */
    public final class SnapshotTransactions implements Transactions {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            Map<Long, Object[]> seatState = new HashMap<>();
            seats.values().forEach(s -> seatState.put(s.id, new Object[] {s.status, s.reservationId}));
            Map<Long, ReservationRow> resv = new LinkedHashMap<>(reservations);
            Map<Long, List<long[]>> resvSeats = new HashMap<>(reservationSeats);
            try {
                return work.get();
            } catch (RuntimeException e) {
                seats.values().forEach(s -> {
                    Object[] st = seatState.get(s.id);
                    s.status = (SeatRepository.Status) st[0];
                    s.reservationId = (Long) st[1];
                });
                reservations.clear();
                reservations.putAll(resv);
                reservationSeats.clear();
                reservationSeats.putAll(resvSeats);
                throw e;
            }
        }
    }

    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    public static AppProperties props(int maxActive) {
        return new AppProperties(
                new AppProperties.Db("x", 3306, "x", "x", "x", "PREFERRED", 1),
                new AppProperties.Redis("x", 6379, null, false, 0),
                new AppProperties.RateLimit(100, 10, 100),
                new AppProperties.Cors(List.of()),
                new AppProperties.Queue(maxActive, 600, 30, 300),
                new AppProperties.Auth(30, false));
    }
}
