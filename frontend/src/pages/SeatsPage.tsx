import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router';
import { api, toApiError, type ApiError, type PerformanceSummary, type Seat, type SeatMap as SeatMapData } from '../api';
import { clearBooking, getBooking, setHold, setShowId, type HeldSeat } from '../booking';
import { dateTimeLabel, seatLabel, won } from '../format';
import { Notice, readNotice, usePoller, type NoticeData } from '../components/bits';
import { SeatLegend, SeatMap } from '../components/SeatMap';
import { PerfSwitcher } from '../components/PerfSwitcher';

/** 좌석 상태 갱신 간격(계약: 3~5초). 탭이 숨겨지면 멈추고, 다시 보이면 즉시 갱신 */
const SEAT_POLL_MS = 4000;
const MAX_SEATS = 4;

export function SeatsPage() {
  const { performanceId: pid } = useParams();
  const perfId = Number(pid);
  // 진입 시 한 번만 읽는다(렌더 중 sessionStorage 상태 변화에 흔들리지 않게)
  const [session] = useState(() => getBooking(perfId));
  // 토큰 없이 바로 들어오면 대기열부터(서버도 막지만 화면에서도 순서를 지킨다)
  if (!session) return <Navigate to={`/book/${perfId}/queue`} replace />;
  // 회차가 바뀌면 화면 상태를 새로 시작
  return <SeatsInner key={perfId} perfId={perfId} />;
}

function SeatsInner({ perfId }: { perfId: number }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [map, setMap] = useState<SeatMapData | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [stale, setStale] = useState(false);
  const [notice, setNotice] = useState<NoticeData | null>(() => readNotice(location.state));
  const [limitMsg, setLimitMsg] = useState('');
  const [pending, setPending] = useState(false);
  const [holdError, setHoldError] = useState<ApiError | null>(null);
  const inFlight = useRef(false);
  const initialized = useRef(false);
  const [perfs, setPerfs] = useState<PerformanceSummary[]>([]);
  const showId = map?.performance.showId ?? null;

  // 같은 공연의 다른 회차 목록(회차 바꾸기용)
  useEffect(() => {
    if (!showId) return;
    const ac = new AbortController();
    api.getShow(showId, ac.signal).then((sh) => setPerfs(sh.performances), () => undefined);
    return () => ac.abort();
  }, [showId]);

  async function switchPerf(nextId: number) {
    const s = getBooking(perfId);
    // 토큰 반납 = 대기·입장 포기 + 선점 해제(계약 4.2 DELETE /api/queue)
    if (s) await api.leaveQueue(s.queueToken).catch(() => undefined);
    clearBooking();
    navigate(`/book/${nextId}/queue`, {
      replace: true,
      state: { showId, notice: { tone: 'info', title: '회차를 바꿨습니다', message: '이전 회차에서 고른 좌석은 풀렸습니다. 새 회차의 대기열에 들어왔습니다.' } },
    });
  }

  const token = () => getBooking(perfId)?.queueToken ?? '';

  /** 토큰 문제는 대기열로 돌려보낸다 */
  const handleTokenError = useCallback(
    (e: ApiError): boolean => {
      if (e.code === 'QUEUE_TOKEN_INVALID') {
        const showId = getBooking(perfId)?.showId ?? null;
        clearBooking();
        navigate(`/book/${perfId}/queue`, {
          replace: true,
          state: { showId, notice: { tone: 'warn', title: '입장 시간이 지나 대기열에 다시 들어왔습니다', message: '순서가 되면 좌석 화면으로 다시 넘어갑니다.' } },
        });
        return true;
      }
      if (e.code === 'QUEUE_NOT_ADMITTED') {
        navigate(`/book/${perfId}/queue`, { replace: true });
        return true;
      }
      return false;
    },
    [navigate, perfId],
  );

  /** 새 배치도 반영: 내가 고른 좌석이 남에게 넘어갔으면 선택에서 빼고 알린다 */
  const apply = useCallback((m: SeatMapData) => {
    setMap(m);
    setShowId(perfId, m.performance.showId);
    const byId = new Map(m.seats.map((s) => [s.id, s]));
    if (!initialized.current) {
      initialized.current = true;
      setSelected(new Set(m.seats.filter((s) => s.status === 'MINE').map((s) => s.id)));
      return;
    }
    setSelected((prev) => {
      const lost = [...prev].filter((id) => {
        const st = byId.get(id)?.status;
        return st === 'HELD' || st === 'SOLD' || st === undefined;
      });
      if (lost.length === 0) return prev;
      const names = lost.map((id) => byId.get(id)).filter((s): s is Seat => !!s).map(seatLabel).join(', ');
      setNotice({ tone: 'warn', title: '고른 좌석 중 일부가 다른 관객에게 넘어갔습니다', message: `${names} 좌석을 선택에서 뺐습니다.` });
      const next = new Set(prev);
      lost.forEach((id) => next.delete(id));
      return next;
    });
  }, [perfId]);

  usePoller(
    async (signal) => {
      try {
        const m = await api.getSeats(perfId, token(), signal);
        setLoadError(null);
        setStale(false);
        apply(m);
        return SEAT_POLL_MS;
      } catch (err) {
        if (signal.aborted) return null;
        const e = toApiError(err);
        if (e.code === 'UNAUTHENTICATED') return null; // 로그인 화면 이동은 AuthProvider가
        if (handleTokenError(e)) return null;
        if (e.code === 'RATE_LIMITED') return Math.max(1, e.retryAfterSec ?? 5) * 1000;
        if (e.code === 'PERFORMANCE_NOT_FOUND') {
          setLoadError(e);
          return null;
        }
        setLoadError(e);
        setStale(true);
        return 6000;
      }
    },
    { enabled: true, hiddenDelayMs: null },
  );

  function toggle(seat: Seat) {
    setLimitMsg('');
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(seat.id)) next.delete(seat.id);
      else if (next.size >= MAX_SEATS) {
        setLimitMsg(`한 번에 ${MAX_SEATS}석까지 고를 수 있습니다. 다른 좌석을 빼고 골라 주세요.`);
        return prev;
      } else next.add(seat.id);
      return next;
    });
  }

  async function hold() {
    if (!map || inFlight.current || selected.size === 0) return;
    inFlight.current = true;
    setPending(true);
    setHoldError(null);
    setNotice(null);
    setLimitMsg('');
    const ids = [...selected];
    try {
      const h = await api.holdSeats(perfId, ids, token());
      const gradeOf = new Map(map.grades.map((g) => [g.code, g]));
      const held: HeldSeat[] = h.seatIds.map((id) => {
        const s = map.seats.find((x) => x.id === id)!;
        const g = gradeOf.get(s.grade);
        return { id, row: s.row, number: s.number, grade: s.grade, gradeName: g?.name ?? s.grade, price: g?.price ?? 0 };
      });
      held.sort((a, b) => a.row.localeCompare(b.row) || a.number - b.number);
      setHold(perfId, h, held, map.performance);
      navigate(`/book/${perfId}/confirm`);
    } catch (err) {
      const e = toApiError(err);
      if (e.code === 'UNAUTHENTICATED') return;
      if (handleTokenError(e)) return;
      if (e.code === 'SEAT_TAKEN') {
        const taken = e.takenSeatIds;
        const names = taken.map((id) => map.seats.find((s) => s.id === id)).filter((s): s is Seat => !!s).map(seatLabel);
        setSelected((prev) => new Set([...prev].filter((id) => !taken.includes(id))));
        setNotice({
          tone: 'warn',
          title: '먼저 선택된 좌석이 있습니다',
          message: names.length ? `${names.join(', ')} 좌석은 다른 관객이 먼저 골랐습니다. 나머지 좌석은 고른 채로 두었습니다. 빠진 만큼 다시 고르거나 그대로 선택 완료를 누르세요.` : e.message,
        });
        api.getSeats(perfId, token()).then(apply, () => undefined);
      } else {
        setHoldError(e);
      }
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }

  async function quit() {
    const s = getBooking(perfId);
    if (s) await api.leaveQueue(s.queueToken).catch(() => undefined);
    clearBooking();
    navigate(map ? `/shows/${map.performance.showId}` : '/', { replace: true });
  }

  if (!map) {
    if (loadError && !stale) {
      return (
        <>
          <h1 className="sr-only" tabIndex={-1}>좌석 선택</h1>
          <Notice tone="error" title="좌석 정보를 불러오지 못했습니다" requestId={loadError.supportId} actions={<Link className="btn" to="/">공연 목록으로</Link>}>
            <p>{loadError.message}</p>
          </Notice>
        </>
      );
    }
    return (
      <div aria-busy="true">
        <h1 className="sr-only" tabIndex={-1}>좌석 선택</h1>
        <p className="sr-only" role="status">좌석 배치도를 불러오는 중입니다.</p>
        {loadError && <Notice tone="warn" title="연결이 불안정합니다"><p>{loadError.message} 잠시 뒤 다시 시도합니다.</p></Notice>}
        <span className="skel skel--map" aria-hidden="true" />
      </div>
    );
  }

  const gradeOf = new Map(map.grades.map((g) => [g.code, g]));
  const picked = map.seats.filter((s) => selected.has(s.id));
  const total = picked.reduce((n, s) => n + (gradeOf.get(s.grade)?.price ?? 0), 0);

  return (
    <div className="seats">
      <div className="seats__head">
        <h1 className="seats__title" tabIndex={-1}>좌석 선택</h1>
        <p className="seats__what">
          <Link to={`/shows/${map.performance.showId}`}>{map.performance.showTitle}</Link>
          <span>{dateTimeLabel(map.performance.startsAt)}</span>
          <span>{map.performance.venue}</span>
        </p>
        <PerfSwitcher
          performances={perfs}
          currentId={perfId}
          pickedCount={picked.length}
          holding={!!map.myHold}
          onSwitch={switchPerf}
        />
      </div>

      {notice && <Notice tone={notice.tone} title={notice.title}>{notice.message && <p>{notice.message}</p>}</Notice>}
      {stale && loadError && (
        <Notice tone="warn" title="좌석 상태를 새로 받지 못하고 있습니다">
          <p>{loadError.message} 화면의 좌석 상태가 최신이 아닐 수 있습니다.</p>
        </Notice>
      )}

      <div className="seats__layout">
        <div className="seats__map">
          <SeatLegend grades={map.grades} />
          <SeatMap map={map} selected={selected} onToggle={toggle} />
          <p className="hint">좌석 상태는 4초마다 새로 고칩니다. 좁은 화면에서는 배치도를 옆으로 밀어 보세요.</p>
        </div>

        <aside className="cart" aria-labelledby="cart-h">
          <h2 id="cart-h" className="cart__title">
            예매 내용 <span className="cart__count">{picked.length}/{MAX_SEATS}석</span>
          </h2>
          <p className="order">
            <strong className="order__title">{map.performance.showTitle}</strong>
            <span className="order__meta">{dateTimeLabel(map.performance.startsAt)}</span>
            <span className="order__meta order__venue">{map.performance.venue}</span>
          </p>
          <p className="sr-only" aria-live="polite">{picked.length}석 선택, 합계 {won(total)}</p>
          {picked.length === 0 ? (
            <p className="muted cart__empty">배치도에서 좌석을 눌러 고르세요. 최대 {MAX_SEATS}석입니다.</p>
          ) : (
            <ul className="seat-list">
              {picked.map((s) => (
                <li key={s.id}>
                  <span className="seat-list__pos">{seatLabel(s)}</span>
                  <span className="seat-list__grade">{gradeOf.get(s.grade)?.name}</span>
                  <span className="seat-list__price">{won(gradeOf.get(s.grade)?.price ?? 0)}</span>
                  <button type="button" className="linkbtn" onClick={() => toggle(s)} aria-label={`${seatLabel(s)} 빼기`}>빼기</button>
                </li>
              ))}
            </ul>
          )}
          <p className="cart__brief" aria-hidden="true">{picked.length ? `${picked.map(seatLabel).join(', ')} (${won(total)})` : '배치도에서 좌석을 눌러 고르세요'}</p>
          {limitMsg && <p className="field__err" role="alert">{limitMsg}</p>}
          <p className="total"><span>합계</span><strong>{won(total)}</strong></p>
          {holdError && (
            <Notice tone={holdError.code === 'RATE_LIMITED' || holdError.code === 'NOT_ON_SALE' ? 'warn' : 'error'} title="좌석을 확보하지 못했습니다" requestId={holdError.supportId}>
              <p>{holdError.message}</p>
            </Notice>
          )}
          <button type="button" className="btn btn--primary btn--block" onClick={() => void hold()} disabled={pending || picked.length === 0}>
            {pending ? '좌석 확보하는 중…' : picked.length === 0 ? '좌석을 골라 주세요' : `${picked.length}석 선택 완료`}
          </button>
          <p className="hint cart__hint">선택 완료를 누르면 좌석이 5분 동안 내 것으로 확보됩니다.</p>
          <button type="button" className="linkbtn" onClick={() => void quit()}>예매 그만두기</button>
        </aside>
      </div>
    </div>
  );
}
