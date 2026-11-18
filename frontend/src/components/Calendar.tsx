import { useMemo, useState } from 'react';
import type { PerformanceSummary } from '../api';
import { dayKey } from '../format';

const WEEK = ['일', '월', '화', '수', '목', '금', '토'];

/** 회차가 있는 날만 고를 수 있는 월 달력(한국 시간 기준) */
export function Calendar({
  performances,
  selected,
  onSelect,
}: {
  performances: PerformanceSummary[];
  selected: string | null;
  onSelect: (day: string) => void;
}) {
  const byDay = useMemo(() => {
    const m = new Map<string, PerformanceSummary[]>();
    for (const p of performances) {
      const k = dayKey(p.startsAt);
      m.set(k, [...(m.get(k) ?? []), p]);
    }
    return m;
  }, [performances]);

  const months = useMemo(() => {
    const set = new Set([...byDay.keys()].map((k) => k.slice(0, 7)));
    return [...set].sort();
  }, [byDay]);

  const initial = Math.max(0, months.indexOf((selected ?? months[0] ?? '').slice(0, 7)));
  const [mi, setMi] = useState(initial);
  const month = months[mi];
  if (!month) return null;

  const [y, m] = month.split('-').map(Number) as [number, number];
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const firstDow = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
  const cells: (number | null)[] = [...Array<null>(firstDow).fill(null), ...Array.from({ length: daysInMonth }, (_, i) => i + 1)];

  return (
    <div className="cal">
      <div className="cal__head">
        <button type="button" className="cal__nav" onClick={() => setMi(mi - 1)} disabled={mi === 0} aria-label="이전 달">
          ‹
        </button>
        <p className="cal__month" aria-live="polite">{y}년 {m}월</p>
        <button type="button" className="cal__nav" onClick={() => setMi(mi + 1)} disabled={mi >= months.length - 1} aria-label="다음 달">
          ›
        </button>
      </div>
      <div className="cal__grid" role="group" aria-label={`${y}년 ${m}월 공연 날짜`}>
        {WEEK.map((w, i) => (
          <span key={w} className={`cal__dow${i === 0 ? ' cal__dow--sun' : ''}`} aria-hidden="true">{w}</span>
        ))}
        {cells.map((d, i) => {
          if (d === null) return <span key={`e${i}`} aria-hidden="true" />;
          const key = `${month}-${String(d).padStart(2, '0')}`;
          const ps = byDay.get(key);
          if (!ps) return <span key={key} className="cal__day cal__day--off" aria-hidden="true">{d}</span>;
          const open = ps.some((p) => p.status === 'ON_SALE');
          return (
            <button
              key={key}
              type="button"
              className={`cal__day cal__day--on${open ? '' : ' cal__day--muted'}`}
              aria-pressed={selected === key}
              aria-label={`${m}월 ${d}일 ${WEEK[(firstDow + d - 1) % 7]}요일, 회차 ${ps.length}개${open ? '' : ', 예매 불가'}`}
              onClick={() => onSelect(key)}
            >
              {d}
            </button>
          );
        })}
      </div>
    </div>
  );
}
