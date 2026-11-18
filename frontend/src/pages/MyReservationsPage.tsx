import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, toApiError, type ApiError, type Reservation } from '../api';
import { useAuth } from '../auth';
import { fullDateTimeLabel, groupCode, seatLabel, won } from '../format';
import { Notice, useCountdown } from '../components/bits';

type Load = { kind: 'loading' } | { kind: 'error'; error: ApiError } | { kind: 'ready'; list: Reservation[] };

export function MyReservationsPage() {
  const { user } = useAuth();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const ac = new AbortController();
    setLoad({ kind: 'loading' });
    api.myReservations(ac.signal).then(
      (list) => setLoad({ kind: 'ready', list }),
      (err) => {
        if (!ac.signal.aborted) setLoad({ kind: 'error', error: toApiError(err) });
      },
    );
    return () => ac.abort();
  }, [attempt]);

  const replace = (r: Reservation) =>
    setLoad((l) => (l.kind === 'ready' ? { kind: 'ready', list: l.list.map((x) => (x.code === r.code ? r : x)) } : l));

  const now = Date.now();
  const upcoming = load.kind === 'ready' ? load.list.filter((r) => r.status === 'CONFIRMED' && Date.parse(r.performance.startsAt) > now) : [];
  const past = load.kind === 'ready' ? load.list.filter((r) => !upcoming.includes(r)) : [];

  return (
    <div className="mine">
      <div className="page-head">
        <h1 tabIndex={-1}>내 예매</h1>
        {user && <p className="lede">{user.name}님 계정으로 예매한 내역입니다. 공연 시작 전까지 취소할 수 있습니다.</p>}
      </div>

      {load.kind === 'loading' && (
        <div aria-busy="true">
          <p className="sr-only" role="status">예매 내역을 불러오는 중입니다.</p>
          <span className="skel skel--block" aria-hidden="true" />
          <span className="skel skel--block" aria-hidden="true" />
        </div>
      )}

      {load.kind === 'error' && load.error.code !== 'UNAUTHENTICATED' && (
        <Notice
          tone="error"
          title="예매 내역을 불러오지 못했습니다"
          requestId={load.error.supportId}
          actions={<button type="button" className="btn" onClick={() => setAttempt((n) => n + 1)}>다시 불러오기</button>}
        >
          <p>{load.error.message}</p>
        </Notice>
      )}

      {load.kind === 'ready' && load.list.length === 0 && (
        <div className="empty">
          <p className="empty__title">아직 예매한 공연이 없습니다</p>
          <p>공연을 고르고 예매하면 여기에서 좌석과 회차를 확인하고 취소할 수 있습니다.</p>
          <Link className="btn btn--primary" to="/">공연 보러 가기</Link>
        </div>
      )}

      {upcoming.length > 0 && (
        <section aria-labelledby="up-h" className="mine__section">
          <h2 id="up-h" className="section-title">다가오는 공연 <span className="section-title__count">{upcoming.length}건</span></h2>
          <ul className="resv-list">
            {upcoming.map((r) => <li key={r.code}><ResvCard r={r} onChange={replace} /></li>)}
          </ul>
        </section>
      )}

      {past.length > 0 && (
        <section aria-labelledby="past-h" className="mine__section">
          <h2 id="past-h" className="section-title">지난 공연·취소한 예매 <span className="section-title__count">{past.length}건</span></h2>
          <ul className="resv-list">
            {past.map((r) => <li key={r.code}><ResvCard r={r} onChange={replace} /></li>)}
          </ul>
        </section>
      )}
    </div>
  );
}

function ResvCard({ r, onChange }: { r: Reservation; onChange: (r: Reservation) => void }) {
  const cancelled = r.status === 'CANCELLED';
  const started = Date.parse(r.performance.startsAt) <= Date.now();
  const state = cancelled ? '취소함' : started ? '관람 완료' : '예매 확정';
  return (
    <article className={`resv${cancelled ? ' resv--cancelled' : ''}`} aria-labelledby={`resv-${r.code}`}>
      <div className="resv__head">
        <h3 id={`resv-${r.code}`} className="resv__title">
          <Link to={`/shows/${r.show.id}`}>{r.show.title}</Link>
        </h3>
        <span className={`tag ${cancelled ? 'tag--closed' : started ? 'tag--closed' : 'tag--on_sale'}`}>{state}</span>
      </div>
      <dl className="resv__facts">
        <div><dt>회차</dt><dd>{fullDateTimeLabel(r.performance.startsAt)}</dd></div>
        <div><dt>장소</dt><dd>{r.show.venue}</dd></div>
        <div>
          <dt>좌석</dt>
          <dd>
            <ul className="seat-list">
              {r.seats.map((s) => (
                <li key={`${s.row}-${s.number}`}>
                  <span className="seat-list__pos">{seatLabel(s)}</span>
                  <span className="seat-list__grade">{s.grade}석</span>
                  <span className="seat-list__price">{won(s.price)}</span>
                </li>
              ))}
            </ul>
          </dd>
        </div>
        <div><dt>합계</dt><dd className="resv__total">{won(r.totalPrice)}</dd></div>
        <div><dt>예매번호</dt><dd className="resv__code">{groupCode(r.code)}</dd></div>
        {cancelled && r.cancelledAt && <div><dt>취소한 시각</dt><dd>{fullDateTimeLabel(r.cancelledAt)}</dd></div>}
      </dl>
      {!cancelled && !started && <CancelArea r={r} onCancelled={onChange} />}
    </article>
  );
}

function CancelArea({ r, onCancelled }: { r: Reservation; onCancelled: (r: Reservation) => void }) {
  const [step, setStep] = useState<'idle' | 'confirm'>('idle');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const inFlight = useRef(false);
  const keepRef = useRef<HTMLButtonElement>(null);
  const openRef = useRef<HTMLButtonElement>(null);
  const wait = useCountdown(error?.code === 'RATE_LIMITED' ? (error.retryAfterSec ?? 10) : null, error);

  async function cancel() {
    if (inFlight.current || wait > 0) return;
    inFlight.current = true;
    setPending(true);
    setError(null);
    try {
      onCancelled(await api.cancelMyReservation(r.code));
    } catch (err) {
      const e = toApiError(err);
      setError(e);
      if (e.code === 'ALREADY_CANCELLED' || e.code === 'PERFORMANCE_CLOSED') setStep('idle');
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }

  return (
    <div className="cancel">
      {step === 'idle' && (
        <button
          ref={openRef}
          type="button"
          className="btn btn--small btn--ghost btn--danger-text"
          onClick={() => {
            setError(null);
            setStep('confirm');
            requestAnimationFrame(() => keepRef.current?.focus());
          }}
        >
          예매 취소
        </button>
      )}
      {step === 'confirm' && (
        <div className="confirm" role="group" aria-labelledby={`cq-${r.code}`}>
          <p className="confirm__q" id={`cq-${r.code}`}>이 예매를 취소할까요?</p>
          <p>좌석 {r.seats.length}석({won(r.totalPrice)})이 취소됩니다. 되돌릴 수 없고, 좌석은 다른 관객이 예매할 수 있게 됩니다.</p>
          <div className="actions">
            <button type="button" className="btn btn--danger" onClick={() => void cancel()} disabled={pending || wait > 0}>
              {pending ? '취소하는 중…' : wait > 0 ? `${wait}초 뒤에 다시 시도` : '예매 취소하기'}
            </button>
            <button
              ref={keepRef}
              type="button"
              className="btn btn--ghost"
              disabled={pending}
              onClick={() => {
                setStep('idle');
                requestAnimationFrame(() => openRef.current?.focus());
              }}
            >
              그대로 두기
            </button>
          </div>
        </div>
      )}
      {error && error.code !== 'UNAUTHENTICATED' && (
        <Notice
          tone={error.code === 'ALREADY_CANCELLED' || error.code === 'PERFORMANCE_CLOSED' || error.code === 'RATE_LIMITED' ? 'warn' : 'error'}
          title={
            error.code === 'ALREADY_CANCELLED'
              ? '이미 취소된 예매입니다'
              : error.code === 'PERFORMANCE_CLOSED'
                ? '시작한 회차는 취소할 수 없습니다'
                : error.code === 'RESERVATION_NOT_FOUND'
                  ? '예매를 찾을 수 없습니다'
                  : '취소하지 못했습니다'
          }
          requestId={error.supportId}
        >
          <p>{error.message}</p>
        </Notice>
      )}
    </div>
  );
}
