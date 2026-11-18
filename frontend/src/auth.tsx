import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { api, onAuthState, onUnauthenticated, type User } from './api';
import { anyBooking, clearBooking } from './booking';

type AuthCtx = {
  /** 앱 시작 시 /auth/me를 받기 전에는 'loading' */
  status: 'loading' | 'ready';
  user: User | null;
  /** 세션이 만료되어 로그아웃된 상태(다시 로그인할 때까지) */
  expired: boolean;
  logout: () => Promise<void>;
};

const Ctx = createContext<AuthCtx | null>(null);

/**
 * 로그인 후 돌아갈 경로는 앱 안의 경로만 허용한다(오픈 리다이렉트 방지).
 * "/"로 시작하고 "//"·"/\"(프로토콜 상대 주소)가 아니며, 같은 출처로 해석되어야 한다.
 */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.includes('\\')) return '/';
  try {
    const u = new URL(next, window.location.origin);
    if (u.origin !== window.location.origin) return '/';
    if (u.pathname === '/login' || u.pathname === '/signup') return '/';
    return u.pathname + u.search;
  } catch {
    return '/';
  }
}

export function loginHref(next: string) {
  return `/login?next=${encodeURIComponent(safeNext(next))}`;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<'loading' | 'ready'>('loading');
  const [user, setUser] = useState<User | null>(null);
  const [expired, setExpired] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  // 로그인 상태는 api 모듈이 me·login·signup 응답을 받을 때마다 알려 준다
  useEffect(() => {
    onAuthState((s) => {
      setUser(s.user);
      if (s.user) setExpired(false);
      setStatus('ready');
    });
    api.me().catch(() => setStatus('ready')); // 실패해도 비로그인으로 화면은 띄운다
    return () => onAuthState(null);
  }, []);

  // 세션 만료(401 UNAUTHENTICATED): 로그인 상태를 비운다.
  // 로그인이 필요한 화면(/book, /me)은 RequireAuth가 만료 안내와 함께 로그인 화면으로 보내고,
  // 그 밖의 화면에서 받았다면 여기서 보낸다.
  const here = location.pathname + location.search;
  useEffect(() => {
    onUnauthenticated(() => {
      setExpired(true);
      setUser(null);
      if (!isProtected(location.pathname)) navigate(loginHref(here), { replace: true, state: { notice: expiredNotice(here) } });
    });
    return () => onUnauthenticated(null);
  }, [navigate, here, location.pathname]);

  const logout = useCallback(async () => {
    const b = anyBooking();
    if (b) await api.leaveQueue(b.queueToken).catch(() => undefined);
    clearBooking();
    // 먼저 메인으로 옮긴다. 로그인이 필요한 화면(내 예매 등)에 머문 채 사용자를 비우면 로그인 화면으로 튕긴다.
    navigate('/', { replace: true });
    await api.logout().catch(() => undefined);
    setUser(null);
  }, [navigate]);

  const value = useMemo(() => ({ status, user, expired, logout }), [status, user, expired, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

const isProtected = (path: string) => path.startsWith('/book/') || path.startsWith('/me/');

function expiredNotice(here: string) {
  return {
    tone: 'warn',
    title: '로그인이 만료되었습니다',
    message: here.startsWith('/book/')
      ? '다시 로그인하면 하던 예매 화면으로 돌아갑니다. 그동안 확보해 둔 좌석은 풀렸을 수 있습니다.'
      : '다시 로그인하면 보던 화면으로 돌아갑니다.',
  };
}

export function useAuth(): AuthCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('AuthProvider 밖에서 useAuth를 썼습니다');
  return v;
}

/** 로그인이 필요한 화면. 비로그인이면 지금 경로를 돌아갈 곳으로 들고 로그인 화면으로 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, user, expired } = useAuth();
  const location = useLocation();
  if (status === 'loading') {
    return <p className="muted" role="status">로그인 상태를 확인하는 중입니다.</p>;
  }
  if (!user) {
    return (
      <Navigate
        to={loginHref(location.pathname + location.search)}
        replace
        state={{
          notice: expired
            ? expiredNotice(location.pathname)
            : { tone: 'info', title: '로그인이 필요합니다', message: '로그인하면 이 화면으로 돌아옵니다.' },
        }}
      />
    );
  }
  return <>{children}</>;
}
