import { useEffect, useRef } from 'react';
import { Link, Navigate, NavLink, Route, Routes, useLocation } from 'react-router';
import { HomePage } from './pages/HomePage';
import { ShowPage } from './pages/ShowPage';
import { QueuePage } from './pages/QueuePage';
import { SeatsPage } from './pages/SeatsPage';
import { ConfirmPage } from './pages/ConfirmPage';
import { CompletePage } from './pages/CompletePage';
import { MyReservationsPage } from './pages/MyReservationsPage';
import { LoginPage, SignupPage } from './pages/AuthPages';
import { NotFoundPage } from './pages/NotFoundPage';
import { Steps } from './components/Steps';
import { loginHref, RequireAuth, useAuth } from './auth';

/** 페이지가 바뀌면 맨 위로, 그리고 본문 제목으로 포커스를 옮겨 화면 낭독기에 전환을 알린다. */
function useRouteFocus() {
  const { pathname } = useLocation();
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    window.scrollTo(0, 0);
    document.querySelector<HTMLElement>('main h1')?.focus({ preventScroll: true });
  }, [pathname]);
}

/** 머리글의 단계 표시: 예매 진행 화면과 완료 화면에서만 */
function HeaderSteps() {
  const { pathname } = useLocation();
  const m = /^\/book\/\d+\/(queue|seats|confirm)$/.exec(pathname);
  const current = m ? { queue: 1, seats: 2, confirm: 3 }[m[1] as 'queue' | 'seats' | 'confirm'] : pathname === '/complete' ? 4 : -1;
  return current < 0 ? null : <Steps current={current} />;
}

/** 로그인 상태 표시: 이름과 로그아웃, 또는 로그인·회원가입 */
function AccountNav() {
  const { status, user, logout } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <span className="account account--loading" aria-hidden="true" />;
  if (!user) {
    const here = location.pathname + location.search;
    const onAuthPage = location.pathname === '/login' || location.pathname === '/signup';
    return (
      <div className="account">
        <NavLink to={onAuthPage ? '/login' : loginHref(here)}>로그인</NavLink>
        <NavLink to="/signup" className="account__signup">회원가입</NavLink>
      </div>
    );
  }
  return (
    <div className="account">
      <span className="account__name">{user.name}님</span>
      <button type="button" className="linkbtn account__logout" onClick={() => void logout()}>로그아웃</button>
    </div>
  );
}

export function App() {
  useRouteFocus();
  const { user } = useAuth();
  return (
    <>
      <a className="skip-link" href="#main">본문으로 건너뛰기</a>
      <header className="site-header">
        <div className="wrap site-header__inner">
          <Link to="/" className="brand" aria-label="미리내 마당, 공연 목록으로">
            <span className="brand__mark" aria-hidden="true">미리내 마당</span>
          </Link>
          <HeaderSteps />
          <nav aria-label="주 메뉴" className="site-nav">
            <NavLink to="/" end>공연</NavLink>
            {user && <NavLink to="/me/reservations">내 예매</NavLink>}
          </nav>
          <AccountNav />
        </div>
      </header>

      <main id="main" className="wrap">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/shows/:id" element={<ShowPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/book/:performanceId/queue" element={<RequireAuth><QueuePage /></RequireAuth>} />
          <Route path="/book/:performanceId/seats" element={<RequireAuth><SeatsPage /></RequireAuth>} />
          <Route path="/book/:performanceId/confirm" element={<RequireAuth><ConfirmPage /></RequireAuth>} />
          <Route path="/complete" element={<CompletePage />} />
          <Route path="/me/reservations" element={<RequireAuth><MyReservationsPage /></RequireAuth>} />
          {/* v2의 예매번호 조회 화면은 내 예매로 대체 */}
          <Route path="/lookup" element={<Navigate to="/me/reservations" replace />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>

      <footer className="site-footer">
        <div className="wrap">
          <p>미리내 마당의 공연, 출연진, 공연장, 포스터는 모두 실습용으로 지어낸 것입니다.</p>
          <p>예매한 내역은 로그인한 뒤 내 예매에서 확인하고 취소할 수 있습니다.</p>
          <p className="site-version">버전 {import.meta.env.VITE_APP_VERSION ?? 'dev'}</p>
        </div>
      </footer>
    </>
  );
}
