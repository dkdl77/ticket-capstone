// 시각은 서버가 UTC ISO 문자열로 주고, 화면은 한국 시간으로 보여 준다.
import type { Genre, SaleStatus } from './api';

const TZ = 'Asia/Seoul';

const fmtDate = new Intl.DateTimeFormat('ko-KR', {
  timeZone: TZ,
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  weekday: 'short',
});
const fmtTime = new Intl.DateTimeFormat('ko-KR', { timeZone: TZ, hour: 'numeric', minute: '2-digit' });
const fmtHm = new Intl.DateTimeFormat('ko-KR', { timeZone: TZ, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });

export function dateParts(iso: string | number) {
  const p = fmtDate.formatToParts(new Date(iso));
  const pick = (type: Intl.DateTimeFormatPartTypes) => p.find((x) => x.type === type)?.value ?? '';
  return { year: Number(pick('year')), month: Number(pick('month')), day: Number(pick('day')), weekday: pick('weekday') };
}

/** 한국 시간 날짜 키 "2026-11-21" */
export function dayKey(iso: string | number) {
  const d = dateParts(iso);
  return `${d.year}-${String(d.month).padStart(2, '0')}-${String(d.day).padStart(2, '0')}`;
}

/** 예: 오후 7:30 */
export function timeLabel(iso: string) {
  return fmtTime.format(new Date(iso));
}

/** 예: 19:30 */
export function hm(iso: string) {
  return fmtHm.format(new Date(iso));
}

/** 예: 11월 21일 (금) 오후 7:30 */
export function dateTimeLabel(iso: string) {
  const d = dateParts(iso);
  return `${d.month}월 ${d.day}일 (${d.weekday}) ${timeLabel(iso)}`;
}

/** 예: 2026년 11월 21일 (금) 오후 7:30 */
export function fullDateTimeLabel(iso: string) {
  return `${dateParts(iso).year}년 ${dateTimeLabel(iso)}`;
}

/** 공연 기간. 예: 2026.11.16 ~ 11.23, 같은 날이면 하나만 */
export function periodLabel(first: string, last: string) {
  const a = dateParts(first);
  const b = dateParts(last);
  const left = `${a.year}.${a.month}.${a.day}`;
  if (dayKey(first) === dayKey(last)) return left;
  return `${left} ~ ${a.year === b.year ? '' : `${b.year}.`}${b.month}.${b.day}`;
}

/** 오늘(한국 시간)부터 남은 날 수. 0이면 오늘 */
export function daysUntil(iso: string, now = Date.now()) {
  const [y1, m1, d1] = dayKey(now).split('-').map(Number) as [number, number, number];
  const [y2, m2, d2] = dayKey(iso).split('-').map(Number) as [number, number, number];
  return Math.round((Date.UTC(y2, m2 - 1, d2) - Date.UTC(y1, m1 - 1, d1)) / 86_400_000);
}

export function dDay(iso: string, now = Date.now()) {
  const n = daysUntil(iso, now);
  return n <= 0 ? 'D-DAY' : `D-${n}`;
}

export function won(n: number) {
  return `${n.toLocaleString('ko-KR')}원`;
}

export function priceRange(min: number, max: number) {
  return min === max ? won(min) : `${won(min)} ~ ${won(max)}`;
}

/** 예매번호를 4자씩 끊어 읽기 쉽게(복사 값은 원본 그대로) */
export function groupCode(code: string) {
  return code.match(/.{1,4}/g)?.join(' ') ?? code;
}

export function normalizeCode(input: string) {
  return input.replace(/[\s-]/g, '').toUpperCase();
}

/** mm:ss */
export function clock(sec: number) {
  const s = Math.max(0, Math.floor(sec));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

export const STATUS_LABEL: Record<SaleStatus, string> = {
  UPCOMING: '오픈 예정',
  ON_SALE: '판매 중',
  SOLD_OUT: '매진',
  CLOSED: '종료',
};

export const GENRE_LABEL: Record<Genre, string> = {
  CONCERT: '콘서트',
  MUSICAL: '뮤지컬',
  PLAY: '연극',
  CLASSIC: '클래식',
};

export const GENRES: Genre[] = ['CONCERT', 'MUSICAL', 'PLAY', 'CLASSIC'];

export function seatLabel(s: { row: string; number: number }) {
  return `${s.row}열 ${s.number}번`;
}
