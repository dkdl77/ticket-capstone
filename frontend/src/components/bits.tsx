import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { SaleStatus } from '../api';
import { STATUS_LABEL } from '../format';

export function StatusTag({ status }: { status: SaleStatus }) {
  return <span className={`tag tag--${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>;
}

type NoticeTone = 'error' | 'warn' | 'info';

export type NoticeData = { tone: NoticeTone; title: string; message?: string };

export function Notice({
  tone,
  title,
  children,
  actions,
  requestId,
}: {
  tone: NoticeTone;
  title: string;
  children?: ReactNode;
  actions?: ReactNode;
  requestId?: string | null;
}) {
  return (
    <div className={`notice notice--${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <p className="notice__title">{title}</p>
      {children && <div className="notice__body">{children}</div>}
      {requestId && <p className="notice__rid">문의할 때 알려 주세요: 요청 ID {requestId}</p>}
      {actions && <div className="notice__actions">{actions}</div>}
    </div>
  );
}

/** 페이지 이동 시 navigation state로 넘겨받은 안내를 보여 준다 */
export function readNotice(state: unknown): NoticeData | null {
  if (typeof state !== 'object' || state === null || !('notice' in state)) return null;
  const n = (state as { notice: unknown }).notice as Partial<NoticeData> | null;
  return n && typeof n.title === 'string' && (n.tone === 'error' || n.tone === 'warn' || n.tone === 'info')
    ? { tone: n.tone, title: n.title, message: typeof n.message === 'string' ? n.message : undefined }
    : null;
}

/** Retry-After 초를 받아 남은 초를 센다. token(오류 객체)이 바뀌면 다시 센다. */
export function useCountdown(seconds: number | null, token: unknown): number {
  const [left, setLeft] = useState(0);
  useEffect(() => {
    if (!seconds || seconds <= 0) {
      setLeft(0);
      return;
    }
    const until = Date.now() + seconds * 1000;
    setLeft(seconds);
    const t = window.setInterval(() => {
      const s = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      setLeft(s);
      if (s === 0) window.clearInterval(t);
    }, 250);
    return () => window.clearInterval(t);
  }, [seconds, token]);
  return left;
}

/** 특정 시각까지 남은 초(1초마다 갱신) */
export function useSecondsUntil(iso: string | null): number | null {
  const [left, setLeft] = useState<number | null>(() => (iso ? Math.ceil((Date.parse(iso) - Date.now()) / 1000) : null));
  useEffect(() => {
    if (!iso) {
      setLeft(null);
      return;
    }
    const tick = () => setLeft(Math.max(0, Math.ceil((Date.parse(iso) - Date.now()) / 1000)));
    tick();
    const t = window.setInterval(tick, 500);
    return () => window.clearInterval(t);
  }, [iso]);
  return left;
}

/**
 * 폴링 반복기.
 * - task가 끝나면 task가 돌려준 지연(ms) 뒤에 다시 실행한다(서버의 pollAfterSeconds, Retry-After 반영).
 * - 탭이 숨겨지면 hiddenDelayMs로 늦추고, null이면 멈췄다가 다시 보일 때 즉시 실행한다.
 * - 컴포넌트가 사라지면(화면 이탈) 진행 중 요청을 abort하고 예약을 지운다.
 */
export function usePoller(
  task: (signal: AbortSignal) => Promise<number | null>,
  opts: { enabled: boolean; hiddenDelayMs: number | null },
) {
  const taskRef = useRef(task);
  taskRef.current = task;
  const { enabled, hiddenDelayMs } = opts;

  useEffect(() => {
    if (!enabled) return;
    const ac = new AbortController();
    let timer: number | undefined;
    let running = false;
    let paused = false;
    let lastDelay = 0;

    const schedule = (delay: number) => {
      window.clearTimeout(timer);
      if (document.hidden) {
        if (hiddenDelayMs === null) {
          paused = true;
          return;
        }
        delay = Math.max(delay, hiddenDelayMs);
      }
      timer = window.setTimeout(run, delay);
    };

    const run = async () => {
      if (ac.signal.aborted || running) return;
      running = true;
      paused = false;
      let next: number | null = null;
      try {
        next = await taskRef.current(ac.signal);
      } finally {
        running = false;
      }
      if (ac.signal.aborted || next === null) return; // null = 폴링 종료
      lastDelay = next;
      schedule(next);
    };

    const onVisibility = () => {
      if (document.hidden) {
        // 이미 예약된 짧은 주기를 숨김 주기로 바꾼다
        if (!running) schedule(lastDelay);
      } else if (paused || !running) {
        window.clearTimeout(timer);
        void run();
      }
    };

    void run();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      ac.abort();
      window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [enabled, hiddenDelayMs]);
}
