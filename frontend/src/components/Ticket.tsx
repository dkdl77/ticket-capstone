import { useState } from 'react';
import type { Reservation } from '../api';
import { fullDateTimeLabel, groupCode, seatLabel, won } from '../format';

/**
 * 예매 내역을 종이 티켓처럼 보여 준다. 오른쪽(모바일은 위) 절취선 너머가 예매번호 스텁이다.
 * 완료 화면과 예매 확인 화면이 함께 쓴다.
 */
export function Ticket({ reservation: r, fresh = false }: { reservation: Reservation; fresh?: boolean }) {
  const cancelled = r.status === 'CANCELLED';
  return (
    <article className={`ticket${cancelled ? ' ticket--cancelled' : ''}`} aria-label="예매 내역">
      <div className="ticket__main">
        <p className={`stamp${fresh ? ' stamp--fresh' : ''}${cancelled ? ' stamp--void' : ''}`} aria-hidden="true">
          {cancelled ? '취소됨' : '예매 확정'}
        </p>
        <h2 className="ticket__title">{r.show.title}</h2>
        <dl className="ticket__facts">
          <div className="span-2">
            <dt>회차</dt>
            <dd>{fullDateTimeLabel(r.performance.startsAt)}</dd>
          </div>
          <div className="span-2">
            <dt>장소</dt>
            <dd>{r.show.venue}</dd>
          </div>
          <div className="span-2">
            <dt>좌석 {r.seats.length}석</dt>
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
          <div>
            <dt>금액</dt>
            <dd>{won(r.totalPrice)}</dd>
          </div>
          <div>
            <dt>예매자</dt>
            <dd>
              {r.name}
              <span className="ticket__email">{r.emailMasked}</span>
            </dd>
          </div>
          {cancelled && (
            <div className="span-2">
              <dt>취소한 시각</dt>
              <dd>{r.cancelledAt ? fullDateTimeLabel(r.cancelledAt) : '정보 없음'}</dd>
            </div>
          )}
        </dl>
      </div>
      <div className="ticket__stub">
        <p className="ticket__code-label" id={`code-${r.code}`}>예매번호</p>
        <p className="ticket__code" aria-labelledby={`code-${r.code}`}>
          <span aria-hidden="true">{groupCode(r.code)}</span>
          <span className="sr-only">{r.code.split('').join(' ')}</span>
        </p>
        <CopyButton text={r.code} />
      </div>
    </article>
  );
}

function CopyButton({ text }: { text: string }) {
  const [state, setState] = useState<'idle' | 'done' | 'fail'>('idle');
  // 클립보드 API는 보안 컨텍스트(HTTPS·localhost)에서만 있다. 없으면 버튼을 숨긴다.
  if (!navigator.clipboard) return null;
  return (
    <>
      <button
        type="button"
        className="btn btn--ghost btn--small"
        onClick={() => {
          navigator.clipboard.writeText(text).then(
            () => setState('done'),
            () => setState('fail'),
          );
        }}
      >
        {state === 'done' ? '복사했습니다' : '예매번호 복사'}
      </button>
      <span className="sr-only" role="status">
        {state === 'done' ? '예매번호를 복사했습니다.' : state === 'fail' ? '복사하지 못했습니다. 번호를 직접 적어 두세요.' : ''}
      </span>
    </>
  );
}
