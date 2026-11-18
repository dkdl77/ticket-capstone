import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api, toApiError, type ApiError, type QueueState, type ShowDetail } from '../api';
import { anyBooking, clearBooking, getBooking, startBooking } from '../booking';
import { dateTimeLabel } from '../format';
import { Notice, readNotice, useCountdown, usePoller } from '../components/bits';
import { PosterArt } from '../components/PosterArt';

/** 숨겨진 탭의 대기 상태 확인 간격. 서버는 30초 동안 폴링이 없으면 대기열에서 뺀다(계약 3.2.1). */
const HIDDEN_POLL_MS = 10_000;

type Phase =
  | { kind: 'entering' }
  | { kind: 'waiting'; position: number; first: number }
  | { kind: 'enterError'; error: ApiError }
  | { kind: 'expired' };

export function QueuePage() {
  const { performanceId: pid } = useParams();
  const perfId = Number(pid);
  const navigate = useNavigate();
  const location = useLocation();
  const stateShowId = (location.state as { showId?: unknown } | null)?.showId;
  const notice = readNotice(location.state);
  const [phase, setPhase] = useState<Phase>({ kind: 'entering' });
  const [netIssue, setNetIssue] = useState(false);
  const [show, setShow] = useState<ShowDetail | null>(null);
  const [enterAttempt, setEnterAttempt] = useState(0);
  const enterRef = useRef<Promise<QueueState> | null>(null);
  const [storedShowId] = useState(() => getBooking(perfId)?.showId ?? null);
  const showId = typeof stateShowId === 'number' ? stateShowId : storedShowId;

  const goSeats = () => navigate(`/book/${perfId}/seats`, { replace: true });

  // 1) 대기열 진입(이 회차 토큰이 없을 때만). StrictMode의 이중 실행에도 POST가 한 번만 나가도록 ref에 보관.
  useEffect(() => {
    if (!Number.isInteger(perfId)) return;
    const existing = getBooking(perfId);
    if (existing) {
      setPhase((p) => (p.kind === 'entering' ? { kind: 'waiting', position: -1, first: -1 } : p));
      return;
    }
    // 다른 회차의 진행 중인 예매가 있으면 그 자리는 반납
    const other = anyBooking();
    if (other) {
      void api.leaveQueue(other.queueToken).catch(() => undefined);
      clearBooking();
    }
    let alive = true;
    if (!enterRef.current) enterRef.current = api.enterQueue(perfId);
    enterRef.current.then(
      (q) => {
        if (!alive) return;
        if (q.queueToken) startBooking(perfId, showId, q.queueToken);
        if (q.status === 'ADMITTED') goSeats();
        else setPhase({ kind: 'waiting', position: q.position, first: q.position });
      },
      (err) => {
        if (!alive) return;
        enterRef.current = null;
        const e = toApiError(err);
        if (e.code === 'UNAUTHENTICATED') return;
        setPhase({ kind: 'enterError', error: e });
      },
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [perfId, enterAttempt]);

  // 공연 정보(제목·포스터) — 있으면 보여 주고, 실패해도 대기에는 영향 없음
  useEffect(() => {
    if (!showId) return;
    const ac = new AbortController();
    api.getShow(showId, ac.signal).then(setShow, () => undefined);
    return () => ac.abort();
  }, [showId]);

  // 2) 대기 상태 폴링: 서버가 알려준 pollAfterSeconds 간격, 숨김 탭은 10초, 429면 Retry-After
  usePoller(
    async (signal) => {
      const s = getBooking(perfId);
      if (!s) return null;
      try {
        const q = await api.queueStatus(s.queueToken, signal);
        setNetIssue(false);
        if (q.status === 'ADMITTED') {
          goSeats();
          return null;
        }
        setPhase((p) => ({
          kind: 'waiting',
          position: q.position,
          first: p.kind === 'waiting' && p.first > 0 ? Math.max(p.first, q.position) : q.position,
        }));
        return Math.min(30, Math.max(1, q.pollAfterSeconds)) * 1000;
      } catch (err) {
        if (signal.aborted) return null;
        const e = toApiError(err);
        if (e.code === 'UNAUTHENTICATED') return null; // 로그인 화면 이동은 AuthProvider가
        if (e.code === 'QUEUE_TOKEN_INVALID') {
          clearBooking();
          setPhase({ kind: 'expired' });
          return null;
        }
        if (e.code === 'RATE_LIMITED') return Math.max(1, e.retryAfterSec ?? 5) * 1000;
        setNetIssue(true); // 네트워크·5xx: 자리를 잃지 않도록 계속 확인
        return 5000;
      }
    },
    { enabled: phase.kind === 'waiting', hiddenDelayMs: HIDDEN_POLL_MS },
  );

  async function leave() {
    const s = getBooking(perfId);
    if (s) await api.leaveQueue(s.queueToken).catch(() => undefined);
    clearBooking();
    navigate(showId ? `/shows/${showId}` : '/', { replace: true });
  }

  const perf = show?.performances.find((p) => p.id === perfId);

  return (
    <div className={`queue${show ? ' queue--with-show' : ''}`}>
      {show && (
        <div className="queue__show" aria-hidden="true">
          <PosterArt showId={show.id} poster={show.poster} title={show.title} genre={show.genre} eager />
        </div>
      )}
      <div className="queue__main">
        {notice && <Notice tone={notice.tone} title={notice.title}>{notice.message && <p>{notice.message}</p>}</Notice>}
        {show && (
          <p className="queue__what">
            {show.title}
            {perf && <span>{dateTimeLabel(perf.startsAt)}</span>}
          </p>
        )}

        {phase.kind === 'entering' && (
          <>
            <h1 className="queue__h" tabIndex={-1}>대기열에 들어가는 중</h1>
            <p className="muted" role="status">잠시만 기다려 주세요.</p>
          </>
        )}

        {phase.kind === 'waiting' && (
          <>
            <h1 className="queue__h" tabIndex={-1}>입장 순서를 기다리고 있습니다</h1>
            {phase.position === 0 ? (
              // 앞에 기다리는 사람은 없지만 입장 인원이 꽉 차 자리가 나기를 기다리는 상태
              <>
                <p className="queue__next" aria-live="polite">다음 순서입니다</p>
                <p className="muted">좌석 화면에 있는 관객 중 한 명이 나가면 바로 들어갑니다.</p>
                {phase.first > 0 && (
                  <progress className="queue__bar" max={phase.first} value={phase.first} aria-label="대기 진행" />
                )}
              </>
            ) : phase.position > 0 ? (
              <>
                <p className="queue__pos" aria-live="polite">
                  <span className="queue__label">내 앞에</span>
                  <span className="queue__n">{phase.position.toLocaleString('ko-KR')}</span>
                  <span className="queue__label">명</span>
                </p>
                {phase.first > 0 && (
                  <progress className="queue__bar" max={phase.first} value={phase.first - phase.position} aria-label="대기 진행" />
                )}
              </>
            ) : (
              <p className="muted" role="status">순서를 확인하는 중입니다.</p>
            )}
            <ul className="queue__tips">
              <li>순서가 되면 좌석 선택 화면으로 자동으로 넘어갑니다.</li>
              <li>새로고침해도 자리는 그대로입니다. 창을 닫거나 30초 넘게 연결이 끊기면 순서를 잃습니다.</li>
            </ul>
            {netIssue && (
              <Notice tone="warn" title="연결이 불안정합니다">
                <p>자리는 그대로입니다. 몇 초 뒤에 다시 확인합니다.</p>
              </Notice>
            )}
            <button type="button" className="btn btn--ghost" onClick={() => void leave()}>대기 그만두기</button>
          </>
        )}

        {phase.kind === 'expired' && (
          <>
            <h1 className="queue__h" tabIndex={-1}>대기 순서가 만료되었습니다</h1>
            <Notice tone="warn" title="대기열에 다시 들어가야 합니다">
              <p>순서 확인이 한동안 끊겨 대기열에서 빠졌습니다. 다시 줄을 서면 맨 뒤에서 시작합니다.</p>
            </Notice>
            <div className="actions">
              <button type="button" className="btn btn--primary" onClick={() => { setPhase({ kind: 'entering' }); setEnterAttempt((n) => n + 1); }}>
                다시 줄 서기
              </button>
              <Link className="btn btn--ghost" to={showId ? `/shows/${showId}` : '/'}>공연 정보로</Link>
            </div>
          </>
        )}

        {phase.kind === 'enterError' && <EnterError error={phase.error} showId={showId} onRetry={() => { setPhase({ kind: 'entering' }); setEnterAttempt((n) => n + 1); }} />}
      </div>
    </div>
  );
}

function EnterError({ error, showId, onRetry }: { error: ApiError; showId: number | null; onRetry: () => void }) {
  const wait = useCountdown(error.code === 'RATE_LIMITED' ? (error.retryAfterSec ?? 10) : null, error);
  const back = <Link className="btn btn--ghost" to={showId ? `/shows/${showId}` : '/'}>공연 정보로 돌아가기</Link>;
  const titles: Record<string, string> = {
    NOT_ON_SALE: '지금은 예매할 수 없는 회차입니다',
    SOLD_OUT: '매진된 회차입니다',
    PERFORMANCE_NOT_FOUND: '회차를 찾을 수 없습니다',
    RATE_LIMITED: '요청이 잠시 막혔습니다',
  };
  const retryable = error.isRetryable;
  return (
    <>
      <h1 className="queue__h" tabIndex={-1}>{titles[error.code] ?? '대기열에 들어가지 못했습니다'}</h1>
      <Notice tone={retryable ? 'error' : 'warn'} title={error.message} requestId={error.supportId}>
        {error.code === 'SOLD_OUT' && <p>다른 날짜나 회차를 골라 보세요.</p>}
      </Notice>
      <div className="actions">
        {retryable && (
          <button type="button" className="btn btn--primary" onClick={onRetry} disabled={wait > 0}>
            {wait > 0 ? `${wait}초 뒤에 다시 시도` : '다시 시도'}
          </button>
        )}
        {back}
      </div>
    </>
  );
}
