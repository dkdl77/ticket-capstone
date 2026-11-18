/** 예매 단계. 실제 순서가 있는 흐름이라 번호를 쓴다. 현재 위치와 남은 단계를 늘 보여 준다. */
export const BOOKING_STEPS = ['회차 선택', '대기', '좌석 선택', '예매 확인', '완료'] as const;

export function Steps({ current }: { current: number }) {
  return (
    <ol className="steps" aria-label={`예매 단계, ${BOOKING_STEPS.length}단계 중 ${current + 1}단계: ${BOOKING_STEPS[current]}`}>
      {BOOKING_STEPS.map((label, i) => (
        <li key={label} className={i === current ? 'is-current' : i < current ? 'is-done' : ''} aria-current={i === current ? 'step' : undefined}>
          <span className="steps__no" aria-hidden="true">{i + 1}</span>
          <span className="steps__label">{label}</span>
        </li>
      ))}
    </ol>
  );
}

