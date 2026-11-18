import { useState } from 'react';
import type { PerformanceSummary } from '../api';
import { dateTimeLabel, STATUS_LABEL } from '../format';

/**
 * 좌석 화면에서 날짜·회차 바꾸기.
 * 대기열 토큰은 회차 단위라서, 바꾸면 지금 토큰을 반납(DELETE /api/queue → 선점도 해제)하고
 * 새 회차의 대기열부터 다시 들어간다. 그래서 확인 단계에서 좌석이 풀린다는 점을 먼저 알린다.
 */
export function PerfSwitcher({
  performances,
  currentId,
  pickedCount,
  holding,
  onSwitch,
}: {
  performances: PerformanceSummary[];
  currentId: number;
  pickedCount: number;
  holding: boolean;
  onSwitch: (performanceId: number) => Promise<void>;
}) {
  const [target, setTarget] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const others = performances.filter((p) => p.id !== currentId);
  if (others.length === 0) return null;

  const loss =
    holding || pickedCount > 0
      ? `지금 고른 좌석 ${pickedCount}석${holding ? '(확보해 둔 좌석 포함)' : ''}이 모두 풀리고, `
      : '';

  return (
    <details className="switch">
      <summary>날짜·회차 바꾸기</summary>
      <fieldset className="switch__body">
        <legend className="sr-only">바꿀 회차</legend>
        <div className="switch__list">
          {others.map((p) => (
            <label key={p.id} className={`switch__opt${p.status !== 'ON_SALE' ? ' is-off' : ''}`}>
              <input
                type="radio"
                name="switch-perf"
                value={p.id}
                disabled={p.status !== 'ON_SALE'}
                checked={target === p.id}
                onChange={() => setTarget(p.id)}
              />
              <span>
                {dateTimeLabel(p.startsAt)}
                {p.status !== 'ON_SALE' && <span className="switch__st">{STATUS_LABEL[p.status]}</span>}
              </span>
            </label>
          ))}
        </div>
        <p className="switch__warn">
          회차를 바꾸면 {loss}새 회차의 대기열에 다시 들어갑니다. 지금 회차로는 되돌아오지 않습니다.
        </p>
        <button
          type="button"
          className="btn"
          disabled={target === null || pending}
          onClick={async () => {
            if (target === null) return;
            setPending(true);
            await onSwitch(target);
          }}
        >
          {pending ? '바꾸는 중…' : '이 회차로 바꾸기'}
        </button>
      </fieldset>
    </details>
  );
}
