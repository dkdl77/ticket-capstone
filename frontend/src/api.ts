// API 호출은 전부 이 모듈을 거친다. 자료형은 docs/ARCHITECTURE.md (v2) 4장 계약과 1:1로 맞춘다.

export type Genre = 'CONCERT' | 'MUSICAL' | 'PLAY' | 'CLASSIC';
export type SaleStatus = 'UPCOMING' | 'ON_SALE' | 'SOLD_OUT' | 'CLOSED';

export type Grade = { code: string; name: string; price: number };
export type Poster = { seed: number; hue: number };

export type ShowSummary = {
  id: number;
  title: string;
  genre: Genre;
  venue: string;
  firstStartsAt: string;
  lastStartsAt: string;
  saleOpensAt: string;
  minPrice: number;
  maxPrice: number;
  status: SaleStatus;
  remainingSeats: number;
  totalSeats: number;
  rank: number | null;
  poster: Poster;
};

export type PerformanceSummary = {
  id: number;
  startsAt: string;
  status: SaleStatus;
  remainingByGrade: { grade: string; remaining: number; total: number }[];
};

export type ShowDetail = ShowSummary & {
  ageRating: string;
  runningMinutes: number;
  cast: string[];
  description: string;
  notices: string[];
  maxSeatsPerBooking: number;
  grades: Grade[];
  performances: PerformanceSummary[];
};

export type QueueState = {
  queueToken?: string;
  status: 'WAITING' | 'ADMITTED';
  position: number;
  admittedUntil: string | null;
  pollAfterSeconds: number;
};

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD' | 'MINE';

export type Seat = { id: number; row: string; number: number; grade: string; status: SeatStatus };

export type Hold = { holdId: string; seatIds: number[]; totalPrice: number; expiresAt: string };

export type SeatMap = {
  performance: { id: number; showId: number; showTitle: string; venue: string; startsAt: string };
  grades: Grade[];
  rows: string[];
  seatsPerRow: number;
  aisleAfter: number[];
  seats: Seat[];
  myHold: Hold | null;
};

export type Reservation = {
  code: string;
  show: { id: number; title: string; venue: string };
  performance: { id: number; startsAt: string };
  seats: { row: string; number: number; grade: string; price: number }[];
  totalPrice: number;
  name: string;
  emailMasked: string;
  status: 'CONFIRMED' | 'CANCELLED';
  createdAt: string;
  cancelledAt: string | null;
};

export type ApiErrorBody = {
  error: { code: string; message: string; requestId: string; details?: unknown };
};

export type ShowQuery = {
  genre?: Genre;
  q?: string;
  status?: SaleStatus;
  sort?: 'date' | 'ranking' | 'opening';
};

// v3: 계정과 로그인 상태
export type User = { id: number; email: string; name: string };
export type AuthState = { user: User | null; csrfToken: string };

/** 계약의 오류 코드 + 프론트 전용 NETWORK_ERROR / BAD_RESPONSE */
export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'SHOW_NOT_FOUND'
  | 'PERFORMANCE_NOT_FOUND'
  | 'NOT_ON_SALE'
  | 'SOLD_OUT'
  | 'QUEUE_TOKEN_INVALID'
  | 'QUEUE_NOT_ADMITTED'
  | 'SEAT_TAKEN'
  | 'HOLD_NOT_FOUND'
  | 'HOLD_EXPIRED'
  | 'RESERVATION_NOT_FOUND'
  | 'UNAUTHENTICATED'
  | 'CSRF_INVALID'
  | 'INVALID_CREDENTIALS'
  | 'ACCOUNT_LOCKED'
  | 'EMAIL_TAKEN'
  | 'BOOKING_LIMIT_EXCEEDED'
  | 'ALREADY_CANCELLED'
  | 'PERFORMANCE_CLOSED'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR'
  | 'NETWORK_ERROR'
  | 'BAD_RESPONSE'
  | (string & {});

export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly requestId: string | null;
  readonly retryAfterSec: number | null;
  readonly details: unknown;

  constructor(opts: {
    code: ErrorCode;
    message: string;
    status: number;
    requestId?: string | null;
    retryAfterSec?: number | null;
    details?: unknown;
  }) {
    super(opts.message);
    this.name = 'ApiError';
    this.code = opts.code;
    this.status = opts.status;
    this.requestId = opts.requestId ?? null;
    this.retryAfterSec = opts.retryAfterSec ?? null;
    this.details = opts.details;
  }

  /** 서버가 처리했는지 확정할 수 없는 실패 → 예매 확정은 같은 Idempotency-Key로 재시도 */
  get isRetryable(): boolean {
    return this.code === 'NETWORK_ERROR' || this.code === 'RATE_LIMITED' || this.status >= 500;
  }

  /** 문의용 요청 ID: 서버 오류(5xx)일 때만 화면에 보여 준다 */
  get supportId(): string | null {
    return this.status >= 500 ? this.requestId : null;
  }

  /** SEAT_TAKEN의 details.seatIds */
  get takenSeatIds(): number[] {
    const d = this.details as { seatIds?: unknown } | undefined;
    return Array.isArray(d?.seatIds) ? d.seatIds.filter((x): x is number => typeof x === 'number') : [];
  }
}

export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err;
  return new ApiError({ code: 'BAD_RESPONSE', status: 0, message: '알 수 없는 오류가 발생했습니다. 페이지를 새로고침해 주세요.' });
}

export function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError';
}

const TIMEOUT_MS = 10_000;

function parseRetryAfter(value: string | null): number | null {
  if (!value) return null;
  const sec = Number(value);
  if (Number.isFinite(sec) && sec >= 0) return Math.ceil(sec);
  const date = Date.parse(value);
  return Number.isNaN(date) ? null : Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

function isApiErrorBody(v: unknown): v is ApiErrorBody {
  if (typeof v !== 'object' || v === null || !('error' in v)) return false;
  const e = (v as { error: unknown }).error;
  return (
    typeof e === 'object' && e !== null &&
    typeof (e as { code?: unknown }).code === 'string' &&
    typeof (e as { message?: unknown }).message === 'string'
  );
}

type Req = {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** 204처럼 본문 없는 성공을 허용 */
  allowEmpty?: boolean;
};

async function rawRequest<T>(path: string, init: Req = {}): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, TIMEOUT_MS);
  const onAbort = () => controller.abort();
  init.signal?.addEventListener('abort', onAbort, { once: true });

  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method: init.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        ...(init.body !== undefined ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
        ...init.headers,
      },
      body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    });
  } catch (err) {
    if (init.signal?.aborted && !timedOut) throw err; // 화면 이탈: 호출자가 무시
    throw new ApiError({
      code: 'NETWORK_ERROR',
      status: 0,
      message: timedOut
        ? '서버 응답이 10초 넘게 없어 기다리기를 멈췄습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'
        : '서버에 연결하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.',
    });
  } finally {
    window.clearTimeout(timer);
    init.signal?.removeEventListener('abort', onAbort);
  }

  const text = await res.text().catch(() => '');
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (res.ok) {
    if (data === null && !init.allowEmpty) {
      throw new ApiError({ code: 'BAD_RESPONSE', status: res.status, message: '서버 응답을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.' });
    }
    return data as T;
  }

  const retryAfterSec = parseRetryAfter(res.headers.get('Retry-After'));
  if (isApiErrorBody(data)) {
    throw new ApiError({
      code: data.error.code,
      message: data.error.message,
      status: res.status,
      requestId: typeof data.error.requestId === 'string' ? data.error.requestId : null,
      retryAfterSec,
      details: data.error.details,
    });
  }
  throw new ApiError({
    code: res.status === 429 ? 'RATE_LIMITED' : res.status >= 500 ? 'INTERNAL_ERROR' : 'BAD_RESPONSE',
    status: res.status,
    retryAfterSec,
    message:
      res.status === 429
        ? '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.'
        : `서버가 요청을 처리하지 못했습니다(HTTP ${res.status}). 잠시 후 다시 시도해 주세요.`,
  });
}

// ── 인증·CSRF (v3) ──
// 세션은 HttpOnly 쿠키라 자바스크립트로 읽지 않는다(같은 출처 fetch에 자동으로 붙음).
// csrfToken은 이 모듈의 메모리에만 둔다. 어떤 저장소에도 넣지 않는다.
let csrfToken: string | null = null;
let csrfLoading: Promise<void> | null = null;
let authListener: ((s: AuthState) => void) | null = null;
let unauthListener: ((e: ApiError) => void) | null = null;

/** 로그인 상태를 받을 때마다(me·login·signup) 알려 줄 곳 */
export function onAuthState(fn: ((s: AuthState) => void) | null) {
  authListener = fn;
}

/** 어디서든 401 UNAUTHENTICATED(세션 만료)를 받으면 알려 줄 곳 */
export function onUnauthenticated(fn: ((e: ApiError) => void) | null) {
  unauthListener = fn;
}

function acceptAuth(s: AuthState): AuthState {
  if (typeof s?.csrfToken === 'string') csrfToken = s.csrfToken;
  authListener?.(s);
  return s;
}

/** GET /auth/me로 로그인 상태와 새 csrfToken을 받는다(동시에 여러 번 불려도 요청은 하나) */
function refreshAuth(): Promise<void> {
  if (!csrfLoading) {
    csrfLoading = rawRequest<AuthState>('/auth/me')
      .then((s) => void acceptAuth(s))
      .finally(() => {
        csrfLoading = null;
      });
  }
  return csrfLoading;
}

/**
 * 모든 API 요청의 입구.
 * - GET·HEAD 외에는 X-CSRF-Token을 붙인다(토큰이 아직 없으면 먼저 /auth/me).
 * - 403 CSRF_INVALID면 토큰을 다시 받아 한 번만 재시도한다.
 * - 401 UNAUTHENTICATED면 등록된 처리기(로그인 상태 비우기 + 로그인 화면)로 알린다.
 */
async function request<T>(path: string, init: Req = {}, retried = false): Promise<T> {
  const method = init.method ?? 'GET';
  const unsafe = method !== 'GET';
  if (unsafe && !csrfToken) await refreshAuth();
  try {
    return await rawRequest<T>(path, {
      ...init,
      headers: { ...init.headers, ...(unsafe && csrfToken ? { 'X-CSRF-Token': csrfToken } : {}) },
    });
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.code === 'CSRF_INVALID' && unsafe && !retried) {
        await refreshAuth();
        return request<T>(path, init, true);
      }
      if (err.code === 'UNAUTHENTICATED') unauthListener?.(err);
    }
    throw err;
  }
}

const tokenHeader = (queueToken: string) => ({ 'X-Queue-Token': queueToken });

export const api = {
  // 인증
  async me(): Promise<AuthState> {
    return acceptAuth(await rawRequest<AuthState>('/auth/me'));
  },
  async signup(body: { email: string; password: string; name: string }): Promise<AuthState> {
    return acceptAuth(await request<AuthState>('/auth/signup', { method: 'POST', body }));
  },
  async login(body: { email: string; password: string }): Promise<AuthState> {
    return acceptAuth(await request<AuthState>('/auth/login', { method: 'POST', body }));
  },
  async logout(): Promise<void> {
    await request<void>('/auth/logout', { method: 'POST', allowEmpty: true });
    csrfToken = null;
    await refreshAuth().catch(() => undefined); // 익명 세션의 새 토큰
  },

  // 내 예매
  myReservations(signal?: AbortSignal): Promise<Reservation[]> {
    return request<{ reservations: Reservation[] }>('/me/reservations', { signal }).then((r) => r.reservations);
  },
  cancelMyReservation(code: string): Promise<Reservation> {
    return request<Reservation>(`/me/reservations/${encodeURIComponent(code)}/cancel`, { method: 'POST' });
  },

  listShows(query: ShowQuery, signal?: AbortSignal): Promise<ShowSummary[]> {
    const p = new URLSearchParams();
    if (query.genre) p.set('genre', query.genre);
    if (query.q) p.set('q', query.q);
    if (query.status) p.set('status', query.status);
    if (query.sort) p.set('sort', query.sort);
    const qs = p.toString();
    return request<{ shows: ShowSummary[] }>(`/shows${qs ? `?${qs}` : ''}`, { signal }).then((r) => r.shows);
  },
  getShow(id: number, signal?: AbortSignal): Promise<ShowDetail> {
    return request<ShowDetail>(`/shows/${encodeURIComponent(String(id))}`, { signal });
  },

  enterQueue(performanceId: number): Promise<QueueState> {
    return request<QueueState>('/queue/enter', { method: 'POST', body: { performanceId } });
  },
  queueStatus(queueToken: string, signal?: AbortSignal): Promise<QueueState> {
    return request<QueueState>('/queue/status', { headers: tokenHeader(queueToken), signal });
  },
  leaveQueue(queueToken: string): Promise<void> {
    return request<void>('/queue', { method: 'DELETE', headers: tokenHeader(queueToken), allowEmpty: true });
  },

  getSeats(performanceId: number, queueToken: string, signal?: AbortSignal): Promise<SeatMap> {
    return request<SeatMap>(`/performances/${encodeURIComponent(String(performanceId))}/seats`, {
      headers: tokenHeader(queueToken),
      signal,
    });
  },
  holdSeats(performanceId: number, seatIds: number[], queueToken: string): Promise<Hold> {
    return request<Hold>(`/performances/${encodeURIComponent(String(performanceId))}/holds`, {
      method: 'POST',
      body: { seatIds },
      headers: tokenHeader(queueToken),
    });
  },
  releaseHold(holdId: string, queueToken: string): Promise<void> {
    return request<void>(`/holds/${encodeURIComponent(holdId)}`, {
      method: 'DELETE',
      headers: tokenHeader(queueToken),
      allowEmpty: true,
    });
  },

  createReservation(holdId: string, queueToken: string, idempotencyKey: string): Promise<Reservation> {
    return request<Reservation>('/reservations', {
      method: 'POST',
      body: { holdId },
      headers: { ...tokenHeader(queueToken), 'Idempotency-Key': idempotencyKey },
    });
  },
};
