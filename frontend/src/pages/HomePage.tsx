import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, toApiError, type ApiError, type Genre, type ShowQuery, type ShowSummary } from '../api';
import { dDay, dateTimeLabel, GENRE_LABEL, GENRES, periodLabel, won } from '../format';
import { Notice, StatusTag, useCountdown } from '../components/bits';
import { PosterArt } from '../components/PosterArt';

type Load<T> = { kind: 'loading' } | { kind: 'error'; error: ApiError } | { kind: 'ready'; data: T };

function useShows(query: ShowQuery, attempt: number): Load<ShowSummary[]> {
  const [state, setState] = useState<Load<ShowSummary[]>>({ kind: 'loading' });
  const key = JSON.stringify(query);
  useEffect(() => {
    const ac = new AbortController();
    setState({ kind: 'loading' });
    api.listShows(JSON.parse(key) as ShowQuery, ac.signal).then(
      (data) => setState({ kind: 'ready', data }),
      (err) => {
        if (!ac.signal.aborted) setState({ kind: 'error', error: toApiError(err) });
      },
    );
    return () => ac.abort();
  }, [key, attempt]);
  return state;
}

function parseGenre(v: string | null): Genre | undefined {
  return GENRES.find((g) => g === v);
}

export function HomePage() {
  const [params, setParams] = useSearchParams();
  const genre = parseGenre(params.get('genre'));
  const q = (params.get('q') ?? '').trim().slice(0, 40);
  const [draft, setDraft] = useState(q);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => setDraft(q), [q]);

  const all = useShows({ genre, q: q || undefined, sort: 'date' }, attempt);
  const ranking = useShows({ genre, sort: 'ranking' }, attempt);
  const opening = useShows({ genre, status: 'UPCOMING', sort: 'opening' }, attempt);

  function onSearch(ev: FormEvent) {
    ev.preventDefault();
    const next = new URLSearchParams(params);
    const v = draft.trim().slice(0, 40);
    if (v) next.set('q', v);
    else next.delete('q');
    setParams(next);
  }

  function genreHref(g?: Genre) {
    const next = new URLSearchParams();
    if (g) next.set('genre', g);
    if (q) next.set('q', q);
    const s = next.toString();
    return s ? `/?${s}` : '/';
  }

  const retry = () => setAttempt((n) => n + 1);

  return (
    <>
      <h1 className="sr-only" tabIndex={-1}>공연 찾기</h1>

      <div className="toolbar">
        <nav className="genres" aria-label="장르">
          <Link to={genreHref()} aria-current={!genre ? 'page' : undefined}>전체</Link>
          {GENRES.map((g) => (
            <Link key={g} to={genreHref(g)} aria-current={genre === g ? 'page' : undefined}>
              {GENRE_LABEL[g]}
            </Link>
          ))}
        </nav>
        <form className="search" role="search" onSubmit={onSearch}>
          <label htmlFor="search-q" className="sr-only">공연 이름이나 장소로 찾기</label>
          <input
            id="search-q"
            type="search"
            name="q"
            maxLength={40}
            placeholder="공연 이름, 장소"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            autoComplete="off"
          />
          <button type="submit" className="btn btn--primary">검색</button>
        </form>
      </div>

      {!q && (
        <div className="lead">
          <section className="ranking" aria-labelledby="rank-h">
            <h2 id="rank-h" className="section-title">{genre ? `${GENRE_LABEL[genre]} 판매 랭킹` : '판매 랭킹'}</h2>
            <RankingList state={ranking} onRetry={retry} />
          </section>
          <section className="opening" aria-labelledby="open-h">
            <h2 id="open-h" className="section-title">티켓 오픈 예정</h2>
            <OpeningList state={opening} onRetry={retry} />
          </section>
        </div>
      )}

      <section className="wall" aria-labelledby="wall-h">
        <h2 id="wall-h" className="section-title">
          {q ? `‘${q}’ 검색 결과` : genre ? `${GENRE_LABEL[genre]} 전체` : '모든 공연'}
          {all.kind === 'ready' && <span className="section-title__count">{all.data.length}개</span>}
        </h2>
        <PosterWall state={all} onRetry={retry} query={q} onClear={() => setParams(genre ? { genre } : {})} />
      </section>
    </>
  );
}

function LoadError({ error, onRetry, title }: { error: ApiError; onRetry: () => void; title: string }) {
  const wait = useCountdown(error.code === 'RATE_LIMITED' ? (error.retryAfterSec ?? 10) : null, error);
  return (
    <Notice
      tone={error.code === 'RATE_LIMITED' ? 'warn' : 'error'}
      title={title}
      requestId={error.supportId}
      actions={
        <button type="button" className="btn" onClick={onRetry} disabled={wait > 0}>
          {wait > 0 ? `${wait}초 뒤에 다시 불러오기` : '다시 불러오기'}
        </button>
      }
    >
      <p>{error.message}</p>
    </Notice>
  );
}

function RankingList({ state, onRetry }: { state: Load<ShowSummary[]>; onRetry: () => void }) {
  if (state.kind === 'loading') return <SkeletonRow count={5} className="ranking__list" />;
  if (state.kind === 'error') return <LoadError error={state.error} onRetry={onRetry} title="랭킹을 불러오지 못했습니다" />;
  // 순위는 전체 순위(rank)가 아니라 받은 목록 안에서의 순서(1부터 연속). 장르를 고르면 그 장르 안의 1, 2, 3…
  const top = state.data.filter((s) => s.rank !== null).slice(0, 5);
  if (top.length === 0) return <p className="muted">아직 판매 기록이 없습니다.</p>;
  return (
    <ol className="ranking__list">
      {top.map((s, i) => (
        <li key={s.id} className={i === 0 ? 'ranking__item ranking__item--first' : 'ranking__item'}>
          <Link to={`/shows/${s.id}`} className="ranking__link">
            {/* 포스터는 그림만. 제목은 아래에 글자로 한 번만 둔다 */}
            <span className="ranking__poster">
              <PosterArt showId={s.id} poster={s.poster} title={s.title} genre={s.genre} />
            </span>
            <span className="ranking__text">
              <span className="ranking__no"><span className="sr-only">판매 </span>{i + 1}<span className="sr-only">위</span></span>
              <span className="ranking__body">
                <span className="ranking__title">{s.title}</span>
                <span className="ranking__meta">{s.venue}</span>
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ol>
  );
}

function OpeningList({ state, onRetry }: { state: Load<ShowSummary[]>; onRetry: () => void }) {
  if (state.kind === 'loading') return <SkeletonRow count={3} className="opening__list" />;
  if (state.kind === 'error') return <LoadError error={state.error} onRetry={onRetry} title="오픈 예정 공연을 불러오지 못했습니다" />;
  if (state.data.length === 0) return <p className="muted">예정된 티켓 오픈이 없습니다.</p>;
  return (
    <ul className="opening__list">
      {state.data.slice(0, 4).map((s) => {
        const d = dDay(s.saleOpensAt);
        return (
          <li key={s.id}>
            <Link to={`/shows/${s.id}`} className="stubrow">
              <span className={`stubrow__dday${d === 'D-DAY' || d === 'D-1' ? ' stubrow__dday--soon' : ''}`}>{d}</span>
              <span className="stubrow__body">
                <span className="stubrow__title">{s.title}</span>
                <span className="stubrow__when">{dateTimeLabel(s.saleOpensAt)} 오픈</span>
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

function PosterWall({
  state,
  onRetry,
  query,
  onClear,
}: {
  state: Load<ShowSummary[]>;
  onRetry: () => void;
  query: string;
  onClear: () => void;
}) {
  if (state.kind === 'loading') return <SkeletonGrid />;
  if (state.kind === 'error') return <LoadError error={state.error} onRetry={onRetry} title="공연 목록을 불러오지 못했습니다" />;
  if (state.data.length === 0) {
    return (
      <div className="empty">
        <p className="empty__title">{query ? `‘${query}’와 맞는 공연이 없습니다` : '이 장르에는 아직 공연이 없습니다'}</p>
        <p>{query ? '공연 이름이나 공연장 이름의 일부로 다시 찾아보세요.' : '다른 장르를 골라 보세요.'}</p>
        {query && <button type="button" className="btn" onClick={onClear}>검색어 지우기</button>}
      </div>
    );
  }
  // 끝난 공연은 뒤로
  const list = [...state.data].sort((a, b) => Number(a.status === 'CLOSED') - Number(b.status === 'CLOSED'));
  return (
    <ul className="grid">
      {list.map((s) => (
        <li key={s.id}>
          <Link to={`/shows/${s.id}`} className={`card${s.status === 'CLOSED' ? ' card--closed' : ''}`}>
            <span className="card__poster">
              <PosterArt showId={s.id} poster={s.poster} title={s.title} genre={s.genre} />
            </span>
            {/* 포스터는 그림만. 제목은 여기에 한 번만. 상태 표시는 그림 위에 얹지 않고 가격 줄에 둔다 */}
            <span className="card__title">{s.title}</span>
            <span className="card__meta">{s.venue}</span>
            <span className="card__meta">{periodLabel(s.firstStartsAt, s.lastStartsAt)}</span>
            <span className="card__price">
              {/* 오픈 예정은 옆의 "… 오픈" 문구가 같은 말이라 매진·종료만 표시 */}
              {(s.status === 'SOLD_OUT' || s.status === 'CLOSED') && <StatusTag status={s.status} />}
              <span>{s.status === 'UPCOMING' ? `${dateTimeLabel(s.saleOpensAt)} 오픈` : `${won(s.minPrice)}부터`}</span>
            </span>
            {s.status === 'ON_SALE' && s.totalSeats > 0 && s.remainingSeats / s.totalSeats < 0.15 && (
              <span className="card__hot">잔여 {s.remainingSeats}석</span>
            )}
          </Link>
        </li>
      ))}
    </ul>
  );
}

function SkeletonRow({ count, className }: { count: number; className: string }) {
  return (
    <div className={className} aria-busy="true">
      <span className="sr-only" role="status">불러오는 중입니다.</span>
      {Array.from({ length: count }, (_, i) => (
        <span key={i} className="skel skel--block" aria-hidden="true" />
      ))}
    </div>
  );
}

function SkeletonGrid() {
  return (
    <div className="grid" aria-busy="true">
      <span className="sr-only" role="status">공연 목록을 불러오는 중입니다.</span>
      {Array.from({ length: 8 }, (_, i) => (
        <span key={i} className="skel skel--poster" aria-hidden="true" />
      ))}
    </div>
  );
}
