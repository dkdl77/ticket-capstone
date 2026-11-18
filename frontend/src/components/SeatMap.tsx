import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { Grade, Seat, SeatMap as SeatMapData } from '../api';
import { won } from '../format';

/**
 * 좌석 배치도.
 * - 무대가 위, 행은 무대에서 가까운 순. aisleAfter 번호 뒤에 통로.
 * - 등급 색은 등급 순서(g0, g1, …) 클래스로, 상태는 seat--* 클래스로 표시한다(인라인 style 없음 → CSP 안전).
 * - 키보드: 배치도 안에서는 좌석 하나만 Tab으로 들어가고(roving tabindex), 화살표로 이동, Home/End로 행 끝, 스페이스·엔터로 선택.
 * - 좌석마다 "A열 5번, R석 99,000원, 선택 가능" 같은 접근성 이름을 준다.
 */
export function SeatMap({
  map,
  selected,
  onToggle,
}: {
  map: SeatMapData;
  selected: Set<number>;
  onToggle: (seat: Seat) => void;
}) {
  const gradeIndex = useMemo(() => new Map(map.grades.map((g, i) => [g.code, i])), [map.grades]);
  const gradeOf = useMemo(() => new Map(map.grades.map((g) => [g.code, g])), [map.grades]);

  // 행 × 번호 격자
  const matrix = useMemo(() => {
    const byKey = new Map(map.seats.map((s) => [`${s.row}-${s.number}`, s]));
    return map.rows.map((row) => Array.from({ length: map.seatsPerRow }, (_, i) => byKey.get(`${row}-${i + 1}`) ?? null));
  }, [map]);

  const firstSelectable = useMemo(() => map.seats.find((s) => s.status === 'AVAILABLE' || s.status === 'MINE') ?? map.seats[0], [map.seats]);
  const [focusId, setFocusId] = useState<number | null>(null);
  const activeId = focusId !== null && map.seats.some((s) => s.id === focusId) ? focusId : firstSelectable?.id ?? null;
  const refs = useRef(new Map<number, HTMLButtonElement>());
  const scrollRef = useRef<HTMLDivElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const [fitted, setFitted] = useState(28);
  const [compact, setCompact] = useState(false);
  const [zoom, setZoom] = useState(false);
  const [overflow, setOverflow] = useState(false);

  // 처음에는 배치도 전체가 화면 폭에 들어오도록 좌석 크기를 고른다(12~34px, 2px 단위 클래스 → 인라인 style 없음).
  // 좁은 화면에서는 행 이름·간격도 줄인다(compact). 좌석이 작아 누르기 어려우면 "좌석 크게 보기"로 30px로 키우고 가로로 민다.
  useEffect(() => {
    const el = boxRef.current;
    if (!el) return;
    const fit = () => {
      const w = el.clientWidth - 4; // 테두리
      const c = w < 640;
      const labelW = c ? 16 : 24;
      const gap = c ? 2 : 4;
      const aisleW = c ? 10 : 16;
      const raw = Math.floor((w - 2 * (labelW + gap) - map.aisleAfter.length * aisleW - 8) / map.seatsPerRow) - gap;
      setCompact(c);
      setFitted(Math.max(12, Math.min(34, raw - (raw % 2))));
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [map.seatsPerRow, map.aisleAfter.length]);

  const canZoom = fitted < 26;
  const size = zoom && canZoom ? 30 : fitted;

  // 넘치는지 확인하고, 넘치면 가운데(무대 정면)를 보여 준다
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const over = el.scrollWidth > el.clientWidth + 1;
    setOverflow(over);
    if (over) el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2;
  }, [size, compact]);
  const aisles = new Set(map.aisleAfter);

  function move(seat: Seat, dr: number, dc: number, edge?: 'start' | 'end') {
    const r = map.rows.indexOf(seat.row);
    let c = seat.number - 1;
    let nr = r;
    if (edge === 'start') c = -1;
    if (edge === 'end') c = map.seatsPerRow;
    // 빈 칸은 건너뛰며 같은 방향으로 찾는다
    for (let step = 0; step < map.seatsPerRow * map.rows.length; step++) {
      if (edge) c += edge === 'start' ? 1 : -1;
      else {
        nr += dr;
        c += dc;
      }
      if (nr < 0 || nr >= map.rows.length || c < 0 || c >= map.seatsPerRow) return;
      const next = matrix[nr]?.[c];
      if (next) {
        setFocusId(next.id);
        refs.current.get(next.id)?.focus();
        return;
      }
      if (edge) continue;
    }
  }

  function onKey(e: KeyboardEvent<HTMLButtonElement>, seat: Seat) {
    const k = e.key;
    if (k === 'ArrowRight') move(seat, 0, 1);
    else if (k === 'ArrowLeft') move(seat, 0, -1);
    else if (k === 'ArrowDown') move(seat, 1, 0);
    else if (k === 'ArrowUp') move(seat, -1, 0);
    else if (k === 'Home') move(seat, 0, 0, 'start');
    else if (k === 'End') move(seat, 0, 0, 'end');
    else return;
    e.preventDefault();
  }

  return (
    <div className={`seatmap sz-${size}${compact ? ' seatmap--compact' : ''}${overflow ? ' is-overflow' : ''}`} ref={boxRef}>
      {canZoom && (
        <div className="seatmap__tools">
          <button type="button" className="btn btn--small btn--ghost" aria-pressed={zoom} onClick={() => setZoom((z) => !z)}>
            {zoom ? '전체 배치도 보기' : '좌석 크게 보기'}
          </button>
          {overflow && <span className="seatmap__hint">배치도를 옆으로 밀어 보세요</span>}
        </div>
      )}
      <div className="seatmap__scroll" ref={scrollRef}>
        <div className="seatmap__inner">
          <div className="stage" aria-hidden="true">무대</div>
          <div
            className="seatmap__rows"
            role="group"
            aria-label="좌석 배치도. 화살표 키로 좌석을 옮겨 다니고 스페이스 키로 고릅니다."
          >
            {matrix.map((cells, ri) => (
              <div className="seatrow" key={map.rows[ri]}>
                <span className="seatrow__label" aria-hidden="true">{map.rows[ri]}</span>
                {cells.map((seat, ci) => (
                  <span key={ci} className={`seatcell${aisles.has(ci + 1) ? ' seatcell--aisle' : ''}`}>
                    {seat ? (
                      <SeatButton
                        seat={seat}
                        grade={gradeOf.get(seat.grade)}
                        gi={gradeIndex.get(seat.grade) ?? 0}
                        selected={selected.has(seat.id)}
                        tabbable={seat.id === activeId}
                        onToggle={onToggle}
                        onKey={onKey}
                        onFocus={() => setFocusId(seat.id)}
                        setRef={(el) => {
                          if (el) refs.current.set(seat.id, el);
                          else refs.current.delete(seat.id);
                        }}
                      />
                    ) : (
                      <span className="seat seat--void" aria-hidden="true" />
                    )}
                  </span>
                ))}
                <span className="seatrow__label" aria-hidden="true">{map.rows[ri]}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function SeatButton({
  seat,
  grade,
  gi,
  selected,
  tabbable,
  onToggle,
  onKey,
  onFocus,
  setRef,
}: {
  seat: Seat;
  grade: Grade | undefined;
  gi: number;
  selected: boolean;
  tabbable: boolean;
  onToggle: (s: Seat) => void;
  onKey: (e: KeyboardEvent<HTMLButtonElement>, s: Seat) => void;
  onFocus: () => void;
  setRef: (el: HTMLButtonElement | null) => void;
}) {
  const selectable = seat.status === 'AVAILABLE' || seat.status === 'MINE';
  const state = selected ? 'picked' : seat.status === 'HELD' ? 'held' : seat.status === 'SOLD' ? 'sold' : 'open';
  const stateLabel = selected ? '내가 고름' : seat.status === 'HELD' ? '다른 관객이 고르는 중' : seat.status === 'SOLD' ? '판매됨' : '선택 가능';
  return (
    <button
      ref={setRef}
      type="button"
      className={`seat g${gi} seat--${state}`}
      tabIndex={tabbable ? 0 : -1}
      aria-pressed={selectable ? selected : undefined}
      aria-disabled={selectable ? undefined : true}
      aria-label={`${seat.row}열 ${seat.number}번, ${grade?.name ?? seat.grade} ${grade ? won(grade.price) : ''}, ${stateLabel}`}
      onClick={() => selectable && onToggle(seat)}
      onKeyDown={(e) => onKey(e, seat)}
      onFocus={onFocus}
    />
  );
}

export function SeatLegend({ grades }: { grades: Grade[] }) {
  return (
    <ul className="legend" aria-label="좌석 범례">
      {grades.map((g, i) => (
        <li key={g.code}>
          <span className={`seat seat--open seat--key g${i}`} aria-hidden="true" />
          {g.name} {won(g.price)}
        </li>
      ))}
      <li><span className="seat seat--picked seat--key g0" aria-hidden="true" />내가 고름</li>
      <li><span className="seat seat--sold seat--key" aria-hidden="true" />선택할 수 없음(판매·선점)</li>
    </ul>
  );
}
