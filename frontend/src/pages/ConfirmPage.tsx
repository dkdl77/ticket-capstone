import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { api, toApiError, type ApiError } from '../api';
import { useAuth } from '../auth';
import { clearBooking, getBooking, setHold, type BookingSession } from '../booking';
import { clock, dateTimeLabel, seatLabel, won } from '../format';
import { Notice, useCountdown, useSecondsUntil } from '../components/bits';
import { uuidv4 } from '../uuid';

/**
 * 예매 확인(v3). 예매자 이름·이메일은 계정에서 오므로 입력 없이
 * 좌석·합계·남은 시간·예매자를 보여 주고 확정만 한다.
 */
export function ConfirmPage() {
  const { performanceId: pid } = useParams();
  const perfId = Number(pid);
  // sessionStorage는 처음 한 번만 읽는다(확정 직후 clearBooking 뒤 다시 렌더될 때 예외가 나지 않게)
  const [s] = useState(() => getBooking(perfId));
  if (!s) return <Navigate to={`/book/${perfId}/queue`} replace />;
  if (!s.hold || Date.parse(s.hold.expiresAt) <= Date.now()) {
    return (
      <Navigate
        to={`/book/${perfId}/seats`}
        replace
        state={s.hold ? { notice: { tone: 'warn', title: '좌석 확보 시간이 지났습니다', message: '좌석을 다시 골라 주세요.' } } : undefined}
      />
    );
  }
  return <ConfirmView perfId={perfId} session={s} hold={s.hold} />;
}

function ConfirmView({ perfId, session, hold }: { perfId: number; session: BookingSession; hold: NonNullable<BookingSession['hold']> }) {
  const navigate = useNavigate();
  const { user } = useAuth();
  const left = useSecondsUntil(hold.expiresAt) ?? 0;
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const keyRef = useRef<string | null>(null);
  const inFlight = useRef(false);
  const finished = useRef(false); // 화면을 떠나는 중이면 더는 상태를 바꾸지 않는다
  const wait = useCountdown(error?.code === 'RATE_LIMITED' ? (error.retryAfterSec ?? 10) : null, error);

  const backToSeats = (title: string, message: string) => {
    finished.current = true;
    navigate(`/book/${perfId}/seats`, { replace: true, state: { notice: { tone: 'warn', title, message } } });
  };

  useEffect(() => {
    if (left > 0 || inFlight.current || finished.current) return;
    setHold(perfId, null);
    backToSeats('좌석 확보 시간이 지났습니다', '좌석이 다시 풀렸습니다. 남아 있는 좌석에서 다시 골라 주세요.');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [left]);

  async function confirm() {
    if (inFlight.current || wait > 0) return;
    // 같은 선점(holdId)을 다시 확정하는 것은 같은 제출의 재시도 → 같은 멱등키
    if (!keyRef.current) keyRef.current = uuidv4();
    inFlight.current = true;
    setPending(true);
    setError(null);
    try {
      const reservation = await api.createReservation(hold.holdId, session.queueToken, keyRef.current);
      finished.current = true;
      clearBooking();
      navigate('/complete', { replace: true, state: { reservation } });
      return;
    } catch (err) {
      const e = toApiError(err);
      if (!e.isRetryable) keyRef.current = null;
      switch (e.code) {
        case 'UNAUTHENTICATED':
          finished.current = true; // 로그인 화면으로 보내는 일은 AuthProvider가 한다
          return;
        case 'HOLD_EXPIRED':
          setHold(perfId, null);
          backToSeats('좌석 확보가 풀렸습니다', e.message);
          return;
        case 'SEAT_TAKEN':
          setHold(perfId, null);
          backToSeats('이미 판매된 좌석이 있습니다', e.message);
          return;
        case 'QUEUE_TOKEN_INVALID':
          finished.current = true;
          clearBooking();
          navigate(`/book/${perfId}/queue`, {
            replace: true,
            state: { showId: session.showId, notice: { tone: 'warn', title: '입장 시간이 지나 대기열에 다시 들어왔습니다', message: '순서가 되면 좌석을 다시 고를 수 있습니다.' } },
          });
          return;
        case 'QUEUE_NOT_ADMITTED':
          finished.current = true;
          navigate(`/book/${perfId}/queue`, { replace: true });
          return;
        default:
          setError(e);
      }
    } finally {
      inFlight.current = false;
      if (!finished.current) setPending(false);
    }
  }

  const urgent = left <= 60;
  const meta = session.meta;
  const retrying = !!error?.isRetryable && keyRef.current !== null;

  return (
    <div className="info">
      <div className="info__head">
        <h1 className="seats__title" tabIndex={-1}>예매 확인</h1>
        <div className={`timer${urgent ? ' timer--urgent' : ''}`} role="timer" aria-label={`좌석 확보 남은 시간 ${Math.floor(left / 60)}분 ${left % 60}초`}>
          <span className="timer__label">좌석 확보 남은 시간</span>
          <span className="timer__clock" aria-hidden="true">{clock(left)}</span>
        </div>
        <p className="sr-only" aria-live="assertive">
          {left === 60 ? '좌석 확보 시간이 1분 남았습니다.' : left === 10 ? '10초 남았습니다.' : ''}
        </p>
      </div>

      <div className="info__layout">
        <section className="summary" aria-labelledby="sum-h">
          <h2 id="sum-h" className="cart__title">예매 내용</h2>
          {meta && (
            <p className="summary__what">
              <strong>{meta.showTitle}</strong>
              <span>{dateTimeLabel(meta.startsAt)}</span>
              <span>{meta.venue}</span>
            </p>
          )}
          <ul className="seat-list">
            {session.heldSeats.map((st) => (
              <li key={st.id}>
                <span className="seat-list__pos">{seatLabel(st)}</span>
                <span className="seat-list__grade">{st.gradeName}</span>
                <span className="seat-list__price">{won(st.price)}</span>
              </li>
            ))}
          </ul>
          <p className="total"><span>합계</span><strong>{won(hold.totalPrice)}</strong></p>
          <Link className="linkbtn" to={`/book/${perfId}/seats`}>좌석 다시 고르기</Link>
        </section>

        <section className="panel confirm-panel" aria-labelledby="who-h">
          <h2 id="who-h" className="cart__title">예매자</h2>
          {user && (
            <dl className="facts">
              <div><dt>이름</dt><dd>{user.name}</dd></div>
              <div><dt>이메일</dt><dd>{user.email}</dd></div>
            </dl>
          )}
          <p className="hint">계정 정보로 예매합니다. 예매한 내역은 내 예매에서 언제든 확인하고 취소할 수 있습니다.</p>

          {error && <ConfirmError error={error} showId={session.showId} />}

          <button type="button" className="btn btn--primary btn--block" onClick={() => void confirm()} disabled={pending || wait > 0}>
            {pending
              ? '예매하는 중…'
              : wait > 0
                ? `${wait}초 뒤에 다시 시도할 수 있습니다`
                : retrying
                  ? `${won(hold.totalPrice)} 예매 다시 시도`
                  : `${won(hold.totalPrice)} 예매 확정`}
          </button>
          <p className="hint">결제는 없습니다. 예매 확정을 누르면 바로 확정됩니다.</p>
        </section>
      </div>
    </div>
  );
}

function ConfirmError({ error, showId }: { error: ApiError; showId: number | null }) {
  switch (error.code) {
    case 'NETWORK_ERROR':
      return (
        <Notice tone="error" title="예매 결과를 확인하지 못했습니다">
          <p>{error.message}</p>
          <p>다시 시도해도 같은 예매로 처리되어 두 번 예매되지 않습니다.</p>
        </Notice>
      );
    case 'RATE_LIMITED':
      return <Notice tone="warn" title="요청이 잠시 막혔습니다"><p>{error.message}</p></Notice>;
    case 'BOOKING_LIMIT_EXCEEDED':
      return (
        <Notice
          tone="warn"
          title="이 회차는 한 계정에 4석까지 예매할 수 있습니다"
          actions={
            <>
              <Link className="btn" to="/me/reservations">내 예매 보기</Link>
            </>
          }
        >
          <p>{error.message}</p>
          <p>이미 예매한 좌석과 합쳐 4석을 넘지 않도록 좌석을 줄여 다시 골라 주세요.</p>
        </Notice>
      );
    case 'NOT_ON_SALE':
      return (
        <Notice tone="warn" title="지금은 예매할 수 없는 회차입니다" actions={showId ? <Link className="btn" to={`/shows/${showId}`}>다른 회차 보기</Link> : undefined}>
          <p>{error.message}</p>
        </Notice>
      );
    default:
      return (
        <Notice tone="error" title={error.status >= 500 ? '예매를 처리하지 못했습니다' : '예매하지 못했습니다'} requestId={error.supportId}>
          <p>{error.message}</p>
        </Notice>
      );
  }
}
