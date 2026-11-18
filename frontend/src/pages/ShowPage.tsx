import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { loginHref, useAuth } from '../auth';
import { api, toApiError, type ApiError, type PerformanceSummary, type ShowDetail } from '../api';
import { dateTimeLabel, dayKey, dateParts, GENRE_LABEL, hm, periodLabel, STATUS_LABEL, won } from '../format';
import { Notice, readNotice, StatusTag } from '../components/bits';
import { PosterArt } from '../components/PosterArt';
import { Calendar } from '../components/Calendar';
import { Steps } from '../components/Steps';

type Load = { kind: 'loading' } | { kind: 'error'; error: ApiError } | { kind: 'ready'; show: ShowDetail };

export function ShowPage() {
  const { id } = useParams();
  const showId = Number(id);
  const valid = Number.isInteger(showId) && showId > 0;
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const location = useLocation();
  const notice = readNotice(location.state);

  useEffect(() => {
    if (!valid) return;
    const ac = new AbortController();
    setLoad({ kind: 'loading' });
    api.getShow(showId, ac.signal).then(
      (show) => setLoad({ kind: 'ready', show }),
      (err) => {
        if (!ac.signal.aborted) setLoad({ kind: 'error', error: toApiError(err) });
      },
    );
    return () => ac.abort();
  }, [showId, valid, attempt]);

  if (!valid || (load.kind === 'error' && load.error.code === 'SHOW_NOT_FOUND')) {
    return (
      <div className="empty">
        <h1 className="empty__title" tabIndex={-1}>찾는 공연이 없습니다</h1>
        <p>주소가 잘못되었거나 내려간 공연입니다.</p>
        <Link className="btn" to="/">공연 목록으로</Link>
      </div>
    );
  }

  if (load.kind === 'loading') {
    return (
      <div className="show" aria-busy="true">
        <p className="sr-only" role="status">공연 정보를 불러오는 중입니다.</p>
        <span className="skel skel--poster show__poster" aria-hidden="true" />
        <div aria-hidden="true">
          <span className="skel skel--title" />
          <span className="skel skel--line" />
          <span className="skel skel--line" />
        </div>
      </div>
    );
  }

  if (load.kind === 'error') {
    return (
      <>
        <h1 className="sr-only" tabIndex={-1}>공연 정보</h1>
        <Notice
          tone="error"
          title="공연 정보를 불러오지 못했습니다"
          requestId={load.error.supportId}
          actions={<button type="button" className="btn" onClick={() => setAttempt((n) => n + 1)}>다시 불러오기</button>}
        >
          <p>{load.error.message}</p>
        </Notice>
      </>
    );
  }

  const s = load.show;
  return (
    <>
      {notice && (
        <div className="page-notice">
          <Notice tone={notice.tone} title={notice.title}>{notice.message && <p>{notice.message}</p>}</Notice>
        </div>
      )}
      <div className="show">
        <div className="show__poster">
          <PosterArt showId={s.id} poster={s.poster} title={s.title} genre={s.genre} size="lg" alt={`${s.title} 포스터`} eager />
        </div>

        <div className="show__info">
          <p className="show__genre">
            {GENRE_LABEL[s.genre]}
            {s.rank !== null && <span className="show__rank">판매 {s.rank}위</span>}
          </p>
          <h1 className="show__title" tabIndex={-1}>{s.title}</h1>
          <StatusTag status={s.status} />
          <dl className="facts">
            <div><dt>기간</dt><dd>{periodLabel(s.firstStartsAt, s.lastStartsAt)}</dd></div>
            <div><dt>장소</dt><dd>{s.venue}</dd></div>
            <div><dt>관람 등급</dt><dd>{s.ageRating}</dd></div>
            <div><dt>관람 시간</dt><dd>{s.runningMinutes}분</dd></div>
            <div><dt>출연</dt><dd>{s.cast.join(', ')}</dd></div>
            <div>
              <dt>가격</dt>
              <dd>
                <ul className="prices">
                  {s.grades.map((g, i) => (
                    <li key={g.code}>
                      <span className={`swatch g${i}`} aria-hidden="true" />
                      <span className="prices__name">{g.name}</span>
                      <span className="prices__won">{won(g.price)}</span>
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          </dl>
        </div>

        <aside className="booker" aria-labelledby="booker-h">
          <h2 id="booker-h" className="booker__title">날짜와 회차 선택</h2>
          <div className="booker__steps"><Steps current={0} /></div>
          <Booker show={s} />
        </aside>

        <div className="show__more">
          <section aria-labelledby="about-h">
            <h2 id="about-h" className="section-title">소개</h2>
            <p className="prose">{s.description}</p>
          </section>
          <section aria-labelledby="notice-h">
            <h2 id="notice-h" className="section-title">유의사항</h2>
            <ul className="bullets">
              {s.notices.map((n) => <li key={n}>{n}</li>)}
            </ul>
          </section>
        </div>
      </div>
    </>
  );
}

function Booker({ show: s }: { show: ShowDetail }) {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [params] = useSearchParams();
  // 로그인하고 돌아왔을 때(?perf=회차) 고르던 회차를 다시 선택해 둔다
  const wanted = s.performances.find((p) => p.id === Number(params.get('perf')));
  const firstOpen = wanted ?? s.performances.find((p) => p.status === 'ON_SALE') ?? s.performances.find((p) => p.status !== 'CLOSED') ?? s.performances[0];
  const [day, setDay] = useState<string | null>(firstOpen ? dayKey(firstOpen.startsAt) : null);
  const dayPerfs = useMemo(() => s.performances.filter((p) => day && dayKey(p.startsAt) === day), [s.performances, day]);
  const [perfId, setPerfId] = useState<number | null>(firstOpen?.id ?? null);

  const pickDay = useCallback(
    (d: string) => {
      setDay(d);
      const ps = s.performances.filter((p) => dayKey(p.startsAt) === d);
      setPerfId((ps.find((p) => p.status === 'ON_SALE') ?? ps[0])?.id ?? null);
    },
    [s.performances],
  );

  const perf = s.performances.find((p) => p.id === perfId) ?? null;

  if (s.performances.length === 0) return <p className="muted">등록된 회차가 없습니다.</p>;

  const dp = day ? dateParts(`${day}T12:00:00+09:00`) : null;

  return (
    <div className="booker__body">
      <Calendar performances={s.performances} selected={day} onSelect={pickDay} />

      {dp && (
        <fieldset className="times">
          <legend>{dp.month}월 {dp.day}일 ({dp.weekday}) 회차</legend>
          <div className="times__list">
            {dayPerfs.map((p, i) => (
              <label key={p.id} className="times__opt">
                <input type="radio" name="perf" checked={perfId === p.id} onChange={() => setPerfId(p.id)} />
                <span>
                  <span className="times__no">{i + 1}회</span>
                  <span className="times__hm">{hm(p.startsAt)}</span>
                  {p.status !== 'ON_SALE' && <span className="times__st">{STATUS_LABEL[p.status]}</span>}
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      )}

      {perf && <Remaining perf={perf} show={s} />}

      {perf && (
        <BookButton
          perf={perf}
          show={s}
          loggedIn={!!user}
          onBook={() => {
            if (user) {
              navigate(`/book/${perf.id}/queue`, { state: { showId: s.id } });
              return;
            }
            // 비로그인: 로그인 후 이 공연·이 회차로 돌아오게
            navigate(loginHref(`/shows/${s.id}?perf=${perf.id}`), {
              state: { notice: { tone: 'info', title: '예매하려면 로그인해 주세요', message: '로그인하면 고르던 회차로 돌아옵니다.' } },
            });
          }}
        />
      )}
    </div>
  );
}

function Remaining({ perf, show }: { perf: PerformanceSummary; show: ShowDetail }) {
  return (
    <div className="remain">
      <p className="remain__h">등급별 가격과 잔여석</p>
      <ul>
        {show.grades.map((g, i) => {
          const r = perf.remainingByGrade.find((x) => x.grade === g.code);
          const left = r?.remaining ?? 0;
          return (
            <li key={g.code} className={left === 0 ? 'remain--none' : ''}>
              <span className={`swatch g${i}`} aria-hidden="true" />
              <span className="remain__name">{g.name} <span className="remain__price">{won(g.price)}</span></span>
              <span className="remain__n">{left === 0 ? '매진' : `${left}석`}</span>
            </li>
          );
        })}
      </ul>
      {/* remainingByGrade는 판매 기준이라 다른 관객이 잠시 확보해 둔 좌석도 남은 좌석으로 셉니다 */}
      <p className="hint remain__note">잔여석은 판매된 좌석만 뺀 수입니다. 다른 관객이 지금 고르고 있는 좌석이 포함되어 있을 수 있어, 좌석 화면에서는 더 적게 보일 수 있습니다.</p>
    </div>
  );
}

function BookButton({ perf, show, loggedIn, onBook }: { perf: PerformanceSummary; show: ShowDetail; loggedIn: boolean; onBook: () => void }) {
  if (perf.status === 'ON_SALE') {
    return (
      <>
        <button type="button" className="btn btn--primary btn--block" onClick={onBook}>예매하기</button>
        {!loggedIn && <p className="hint">예매는 로그인한 뒤에 할 수 있습니다.</p>}
      </>
    );
  }
  const label =
    perf.status === 'UPCOMING' ? `${dateTimeLabel(show.saleOpensAt)} 오픈` : perf.status === 'SOLD_OUT' ? '매진된 회차입니다' : '끝난 회차입니다';
  return (
    <>
      <button type="button" className="btn btn--primary btn--block" disabled>{label}</button>
      {perf.status === 'SOLD_OUT' && <p className="hint">취소표가 나오면 다시 예매할 수 있습니다. 다른 회차를 골라 보세요.</p>}
      {perf.status === 'UPCOMING' && <p className="hint">오픈 시각이 지나면 이 화면에서 바로 예매할 수 있습니다.</p>}
    </>
  );
}
