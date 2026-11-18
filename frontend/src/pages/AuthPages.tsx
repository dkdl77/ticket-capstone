import { useId, useRef, useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, toApiError, type ApiError } from '../api';
import { safeNext, useAuth } from '../auth';
import { Notice, readNotice, useCountdown } from '../components/bits';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function waitLabel(sec: number) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `${m}분 ${s}초` : `${s}초`;
}

/** 로그인·가입 공용 틀: 이미 로그인했으면 돌아갈 곳으로 */
function useAuthPage() {
  const { status, user } = useAuth();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const location = useLocation();
  return { status, user, next, notice: readNotice(location.state) };
}

function PasswordField({
  id,
  label,
  value,
  onChange,
  autoComplete,
  error,
  hint,
  disabled,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  autoComplete: 'current-password' | 'new-password';
  error?: string;
  hint?: string;
  disabled: boolean;
}) {
  const [show, setShow] = useState(false);
  const describedBy = [hint ? `${id}-hint` : '', error ? `${id}-err` : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <div className="pw">
        <input
          id={id}
          name="password"
          type={show ? 'text' : 'password'}
          autoComplete={autoComplete}
          maxLength={72}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
        />
        <button type="button" className="pw__toggle" onClick={() => setShow((v) => !v)} aria-pressed={show} aria-controls={id}>
          {show ? '숨기기' : '보기'}
        </button>
      </div>
      {hint && <p className="hint" id={`${id}-hint`}>{hint}</p>}
      {error && <p className="field__err" id={`${id}-err`}>{error}</p>}
    </div>
  );
}

export function LoginPage() {
  const { status, user, next, notice } = useAuthPage();
  const navigate = useNavigate();
  const uid = useId();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errs, setErrs] = useState<{ email?: string; password?: string }>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  const lockedCode = error?.code === 'ACCOUNT_LOCKED' || error?.code === 'RATE_LIMITED';
  const wait = useCountdown(lockedCode ? (error?.retryAfterSec ?? 60) : null, error);

  if (status === 'ready' && user && !pending) return <Navigate to={next} replace />;

  async function onSubmit(ev: FormEvent) {
    ev.preventDefault();
    if (inFlight.current || wait > 0) return;
    const e: typeof errs = {};
    if (!EMAIL_RE.test(email.trim())) e.email = '이메일을 확인해 주세요. 예: name@example.com';
    if (!password) e.password = '비밀번호를 입력해 주세요.';
    setErrs(e);
    if (e.email || e.password) {
      document.getElementById(e.email ? `${uid}-email` : `${uid}-pw`)?.focus();
      return;
    }
    inFlight.current = true;
    setPending(true);
    setError(null);
    try {
      await api.login({ email: email.trim(), password });
      setPassword(''); // 비밀번호는 메모리에서도 바로 지운다
      navigate(next, { replace: true });
    } catch (err) {
      setError(toApiError(err));
      setPassword('');
      setPending(false);
    } finally {
      inFlight.current = false;
    }
  }

  return (
    <div className="auth">
      <h1 className="auth__title" tabIndex={-1}>로그인</h1>
      {notice && <Notice tone={notice.tone} title={notice.title}>{notice.message && <p>{notice.message}</p>}</Notice>}
      <form className="form panel" onSubmit={onSubmit} noValidate>
        <div className="field">
          <label htmlFor={`${uid}-email`}>이메일</label>
          <input
            id={`${uid}-email`}
            name="email"
            type="email"
            inputMode="email"
            autoComplete="username"
            spellCheck={false}
            value={email}
            disabled={pending}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={errs.email ? true : undefined}
            aria-describedby={errs.email ? `${uid}-email-err` : undefined}
          />
          {errs.email && <p className="field__err" id={`${uid}-email-err`}>{errs.email}</p>}
        </div>
        <PasswordField id={`${uid}-pw`} label="비밀번호" value={password} onChange={setPassword} autoComplete="current-password" error={errs.password} disabled={pending} />

        {error && (
          <Notice
            tone={error.code === 'INVALID_CREDENTIALS' || lockedCode ? 'warn' : 'error'}
            title={
              error.code === 'INVALID_CREDENTIALS'
                ? '이메일 또는 비밀번호가 맞지 않습니다'
                : error.code === 'ACCOUNT_LOCKED'
                  ? '로그인이 잠시 잠겼습니다'
                  : error.code === 'RATE_LIMITED'
                    ? '요청이 잠시 막혔습니다'
                    : '로그인하지 못했습니다'
            }
            requestId={error.supportId}
          >
            <p>{error.message}</p>
            {error.code === 'ACCOUNT_LOCKED' && wait > 0 && <p>{waitLabel(wait)} 뒤에 다시 시도할 수 있습니다.</p>}
          </Notice>
        )}

        <button type="submit" className="btn btn--primary btn--block" disabled={pending || wait > 0}>
          {pending ? '로그인하는 중…' : wait > 0 ? `${waitLabel(wait)} 뒤에 다시 시도` : '로그인'}
        </button>
        <p className="auth__alt">
          계정이 없나요? <Link to={`/signup?next=${encodeURIComponent(next)}`}>회원가입</Link>
        </p>
      </form>
    </div>
  );
}

export function SignupPage() {
  const { status, user, next, notice } = useAuthPage();
  const navigate = useNavigate();
  const uid = useId();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [errs, setErrs] = useState<{ email?: string; password?: string; name?: string }>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  const wait = useCountdown(error?.code === 'RATE_LIMITED' ? (error.retryAfterSec ?? 30) : null, error);

  if (status === 'ready' && user && !pending) return <Navigate to={next} replace />;

  async function onSubmit(ev: FormEvent) {
    ev.preventDefault();
    if (inFlight.current || wait > 0) return;
    const e: typeof errs = {};
    if (!EMAIL_RE.test(email.trim())) e.email = '이메일을 확인해 주세요. 예: name@example.com';
    if (password.length < 8 || password.length > 72) e.password = '비밀번호는 8자 이상 72자 이하로 정해 주세요.';
    const n = name.trim();
    if (!n) e.name = '이름을 입력해 주세요.';
    else if (n.length > 40) e.name = '이름은 40자까지 입력할 수 있습니다.';
    setErrs(e);
    const first = e.email ? 'email' : e.password ? 'pw' : e.name ? 'name' : null;
    if (first) {
      document.getElementById(`${uid}-${first}`)?.focus();
      return;
    }
    inFlight.current = true;
    setPending(true);
    setError(null);
    try {
      await api.signup({ email: email.trim(), password, name: n });
      setPassword('');
      navigate(next, { replace: true });
    } catch (err) {
      setError(toApiError(err));
      setPending(false);
    } finally {
      inFlight.current = false;
    }
  }

  return (
    <div className="auth">
      <h1 className="auth__title" tabIndex={-1}>회원가입</h1>
      {notice && <Notice tone={notice.tone} title={notice.title}>{notice.message && <p>{notice.message}</p>}</Notice>}
      <form className="form panel" onSubmit={onSubmit} noValidate>
        <div className="field">
          <label htmlFor={`${uid}-email`}>이메일</label>
          <input
            id={`${uid}-email`}
            name="email"
            type="email"
            inputMode="email"
            autoComplete="username"
            spellCheck={false}
            value={email}
            disabled={pending}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={errs.email ? true : undefined}
            aria-describedby={errs.email ? `${uid}-email-err` : undefined}
          />
          {errs.email && <p className="field__err" id={`${uid}-email-err`}>{errs.email}</p>}
        </div>
        <PasswordField
          id={`${uid}-pw`}
          label="비밀번호"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
          hint="8자 이상 72자 이하"
          error={errs.password}
          disabled={pending}
        />
        <div className="field">
          <label htmlFor={`${uid}-name`}>이름</label>
          <input
            id={`${uid}-name`}
            name="name"
            autoComplete="name"
            maxLength={40}
            value={name}
            disabled={pending}
            onChange={(e) => setName(e.target.value)}
            aria-invalid={errs.name ? true : undefined}
            aria-describedby={`${uid}-name-hint${errs.name ? ` ${uid}-name-err` : ''}`}
          />
          <p className="hint" id={`${uid}-name-hint`}>예매자 이름으로 쓰입니다.</p>
          {errs.name && <p className="field__err" id={`${uid}-name-err`}>{errs.name}</p>}
        </div>

        {error && (
          <Notice
            tone={error.code === 'EMAIL_TAKEN' || error.code === 'VALIDATION_ERROR' || error.code === 'RATE_LIMITED' ? 'warn' : 'error'}
            title={
              error.code === 'EMAIL_TAKEN'
                ? '이미 가입된 이메일입니다'
                : error.code === 'VALIDATION_ERROR'
                  ? '입력 내용을 확인해 주세요'
                  : error.code === 'RATE_LIMITED'
                    ? '요청이 잠시 막혔습니다'
                    : '가입하지 못했습니다'
            }
            requestId={error.supportId}
            actions={error.code === 'EMAIL_TAKEN' ? <Link className="btn" to={`/login?next=${encodeURIComponent(next)}`}>이 이메일로 로그인</Link> : undefined}
          >
            <p>{error.message}</p>
          </Notice>
        )}

        <button type="submit" className="btn btn--primary btn--block" disabled={pending || wait > 0}>
          {pending ? '가입하는 중…' : wait > 0 ? `${wait}초 뒤에 다시 시도` : '가입하고 로그인'}
        </button>
        <p className="auth__alt">
          이미 계정이 있나요? <Link to={`/login?next=${encodeURIComponent(next)}`}>로그인</Link>
        </p>
      </form>
    </div>
  );
}
