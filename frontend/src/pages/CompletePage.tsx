import { Link, Navigate, useLocation } from 'react-router';
import type { Reservation } from '../api';
import { Ticket } from '../components/Ticket';

function readReservation(state: unknown): Reservation | null {
  if (typeof state !== 'object' || state === null || !('reservation' in state)) return null;
  const r = (state as { reservation: unknown }).reservation;
  return typeof r === 'object' && r !== null && typeof (r as Reservation).code === 'string' ? (r as Reservation) : null;
}

/**
 * 예매 결과는 라우터 history state로만 넘겨받는다(URL·localStorage에 남기지 않음).
 * 새로고침하거나 주소로 바로 들어오면 내용이 없으므로 예매 확인 화면으로 보낸다.
 */
export function CompletePage() {
  const location = useLocation();
  const r = readReservation(location.state);
  if (!r) return <Navigate to="/me/reservations" replace />;

  return (
    <div className="complete">
      <div className="page-head">
        <h1 tabIndex={-1}>예매했습니다</h1>
        <p className="lede">
          예매 내역은 내 예매에서 언제든 확인하고 취소할 수 있습니다. 예매번호는 공연장에서 확인할 때 씁니다.
        </p>
      </div>
      <Ticket reservation={r} fresh />
      <div className="actions">
        <Link className="btn btn--primary" to="/me/reservations">내 예매 보기</Link>
        <Link className="btn btn--ghost" to="/">다른 공연 보기</Link>
      </div>
    </div>
  );
}
