import { useId, useMemo, type ReactElement } from 'react';
import type { Genre, Poster as PosterSpec } from '../api';
import { GENRE_LABEL } from '../format';

/**
 * 생성형 포스터. API의 poster { seed, hue }와 제목·장르만으로 리소(공판화) 인쇄 느낌의 포스터를 그린다.
 * - 같은 입력이면 항상 같은 그림(시드 고정 난수).
 * - 두 가지 잉크(hue와 시드로 정한 보색 계열)를 곱하기(multiply)로 겹치고, 판 어긋남·망점·종이 질감을 흉내 낸다.
 * - 장르마다 모티프가 다르다: 콘서트=음파 동심원, 뮤지컬=막과 조명, 연극=무대 세트 도형, 클래식=오선과 홀의 아치.
 * - 색은 전부 SVG 속성(fill/stroke)으로 준다. 인라인 style 속성이 없어서 CSP style-src 'self'와 충돌하지 않는다.
 */

type Props = {
  poster: PosterSpec;
  title: string;
  genre: Genre;
  /** 작은 썸네일: 글자를 빼고 그림만 */
  bare?: boolean;
  className?: string;
  /** 접근성 이름. 옆에 제목 텍스트가 있으면 생략(장식으로 처리) */
  label?: string;
};

const W = 300;
const H = 420;

function rng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const hsl = (h: number, s: number, l: number) => `hsl(${((h % 360) + 360) % 360} ${s}% ${l}%)`;

/** 제목을 포스터 폭에 맞게 줄바꿈(어절 우선, 긴 어절은 글자 단위) */
function wrapTitle(title: string, maxChars: number): string[] {
  const words = title.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let cur = '';
  for (const w of words) {
    if (w.length > maxChars) {
      if (cur) lines.push(cur);
      for (let i = 0; i < w.length; i += maxChars) lines.push(w.slice(i, i + maxChars));
      cur = lines.pop() ?? '';
      continue;
    }
    const next = cur ? `${cur} ${w}` : w;
    if (next.length > maxChars) {
      lines.push(cur);
      cur = w;
    } else cur = next;
  }
  if (cur) lines.push(cur);
  return lines;
}

export function Poster({ poster, title, genre, bare = false, className, label }: Props) {
  const rawId = useId();
  const id = rawId.replace(/[^a-zA-Z0-9]/g, '');

  const art = useMemo(() => {
    const r = rng(poster.seed * 9973 + poster.hue);
    const hue = poster.hue;
    const offsets = [150, 180, 205, 40, 300];
    const hueB = hue + offsets[Math.floor(r() * offsets.length)]!;
    return {
      paper: hsl(hue, 30, 93),
      inkA: hsl(hue, 78, 50),
      inkB: hsl(hueB, 88, 60),
      dark: hsl(hue, 55, 15),
      // 두 번째 판의 어긋남
      shift: { x: Math.round((r() - 0.5) * 7), y: Math.round((r() - 0.5) * 7) },
    };
  }, [poster.seed, poster.hue]);

  // 모티프는 자체 난수로 그린다(StrictMode에서 두 번 계산돼도 결과가 같도록 순수 함수 유지)
  const motif = useMemo(() => drawMotif(genre, art, rng(poster.seed * 31 + 7), id), [genre, art, poster.seed, id]);

  const lines = useMemo(() => {
    const ls = wrapTitle(title, 7).slice(0, 4);
    const longest = Math.max(...ls.map((l) => l.length), 1);
    const size = Math.min(52, Math.floor(252 / longest));
    return { ls, size };
  }, [title]);

  const titleY = H - 26 - (lines.ls.length - 1) * lines.size * 1.08;
  // 제목 영역 위쪽 경계. 그림은 이 선 위로만 그려서(클립) 어떤 장르·크기에서도 제목과 겹치지 않게 한다.
  const titleTop = bare ? H : Math.round(titleY - lines.size * 0.95 - 16);

  return (
    <svg
      className={`poster${className ? ` ${className}` : ''}`}
      viewBox={`0 0 ${W} ${H}`}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
      preserveAspectRatio="xMidYMid slice"
    >
      <defs>
        <pattern id={`dot-${id}`} width="7" height="7" patternUnits="userSpaceOnUse" patternTransform="rotate(22)">
          <circle cx="3.5" cy="3.5" r="2.1" fill={art.inkB} />
        </pattern>
        <pattern id={`dotA-${id}`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(-15)">
          <circle cx="3" cy="3" r="1.5" fill={art.inkA} />
        </pattern>
        <clipPath id={`art-${id}`}>
          <rect width={W} height={titleTop} />
        </clipPath>
        <filter id={`grain-${id}`} x="0" y="0" width="100%" height="100%">
          <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" seed={poster.seed % 997} />
          <feColorMatrix type="saturate" values="0" />
          <feComponentTransfer>
            <feFuncA type="table" tableValues="0 0.22" />
          </feComponentTransfer>
        </filter>
      </defs>

      <rect width={W} height={H} fill={art.paper} />
      <g clipPath={`url(#art-${id})`}>
        <g className="poster__ink">{motif.a}</g>
        <g className="poster__ink" transform={`translate(${art.shift.x} ${art.shift.y})`}>{motif.b}</g>
      </g>
      {!bare && <line x1="20" x2={W - 20} y1={titleTop} y2={titleTop} stroke={art.dark} strokeWidth="3" />}

      {!bare && (
        <>
          <text x="20" y="34" className="poster__genre" fill={art.dark}>
            {GENRE_LABEL[genre]}
          </text>
          <g className="poster__ink">
            {lines.ls.map((l, i) => (
              <text
                key={`b${i}`}
                x={20 + art.shift.x / 2 + 2}
                y={titleY + i * lines.size * 1.08 + 2}
                fontSize={lines.size}
                className="poster__title"
                fill={art.inkB}
              >
                {l}
              </text>
            ))}
          </g>
          {lines.ls.map((l, i) => (
            <text key={i} x="20" y={titleY + i * lines.size * 1.08} fontSize={lines.size} className="poster__title" fill={art.dark}>
              {l}
            </text>
          ))}
        </>
      )}

      <rect width={W} height={H} filter={`url(#grain-${id})`} />
    </svg>
  );
}

type Art = { paper: string; inkA: string; inkB: string; dark: string };

/** 장르별 모티프. a = 첫 번째 판(inkA·dark), b = 두 번째 판(inkB, 어긋나게 찍힘) */
function drawMotif(genre: Genre, art: Art, r: () => number, id: string): { a: ReactElement; b: ReactElement } {
  const { inkA, inkB, dark } = art;
  const top = 250; // 그림이 차지하는 위쪽 영역 높이(아래는 제목)

  if (genre === 'CONCERT') {
    const cx = 70 + r() * 160;
    const cy = 90 + r() * 90;
    const rings = 6 + Math.floor(r() * 4);
    const step = 14 + r() * 8;
    const bars = 13;
    return {
      a: (
        <>
          {Array.from({ length: rings }, (_, i) => (
            <circle key={i} cx={cx} cy={cy} r={18 + i * step} fill="none" stroke={inkA} strokeWidth={i % 2 ? 3 : 8} />
          ))}
          {Array.from({ length: bars }, (_, i) => {
            const h = 10 + r() * 60;
            return <rect key={`bar${i}`} x={20 + i * 20.5} y={top + 20 - h} width="12" height={h} fill={dark} />;
          })}
        </>
      ),
      b: (
        <>
          <circle cx={cx + 30 - r() * 60} cy={cy + 20} r={60 + r() * 40} fill={`url(#dot-${id})`} />
          <circle cx={cx} cy={cy} r={16} fill={inkB} />
        </>
      ),
    };
  }

  if (genre === 'MUSICAL') {
    const scallops = 5 + Math.floor(r() * 3);
    const sw = W / scallops;
    const drop = 40 + r() * 30;
    let curtain = `M0 0 H${W} V${drop}`;
    for (let i = scallops; i > 0; i--) curtain += ` Q${(i - 0.5) * sw} ${drop + 34} ${(i - 1) * sw} ${drop}`;
    curtain += ' Z';
    const lx = 40 + r() * 220;
    const stars = Array.from({ length: 5 + Math.floor(r() * 4) }, () => ({ x: 20 + r() * 260, y: drop + 50 + r() * 140, s: 4 + r() * 7 }));
    return {
      a: (
        <>
          <path d={curtain} fill={inkA} />
          <path d={`M0 0 Q30 ${top * 0.6} 8 ${top + 20} H0 Z`} fill={inkA} />
          <path d={`M${W} 0 Q${W - 30} ${top * 0.6} ${W - 8} ${top + 20} H${W} Z`} fill={inkA} />
          {stars.map((s, i) => (
            <path
              key={i}
              d={`M${s.x} ${s.y - s.s} L${s.x + s.s * 0.3} ${s.y - s.s * 0.3} L${s.x + s.s} ${s.y} L${s.x + s.s * 0.3} ${s.y + s.s * 0.3} L${s.x} ${s.y + s.s} L${s.x - s.s * 0.3} ${s.y + s.s * 0.3} L${s.x - s.s} ${s.y} L${s.x - s.s * 0.3} ${s.y - s.s * 0.3} Z`}
              fill={dark}
            />
          ))}
        </>
      ),
      b: (
        <>
          <path d={`M${lx - 10} ${drop} L${lx + 10} ${drop} L${150 + 90} ${top + 10} L${150 - 90} ${top + 10} Z`} fill={`url(#dot-${id})`} />
          <ellipse cx="150" cy={top + 10} rx="92" ry="16" fill={inkB} />
        </>
      ),
    };
  }

  if (genre === 'PLAY') {
    const blocks = Array.from({ length: 3 + Math.floor(r() * 2) }, (_, i) => {
      const w = 50 + r() * 70;
      const h = 90 + r() * 120;
      return { x: 14 + i * 70 + r() * 20, y: top - h + 10, w, h };
    });
    const door = blocks[Math.floor(r() * blocks.length)]!;
    const mx = 60 + r() * 180;
    const my = 50 + r() * 50;
    return {
      a: (
        <>
          {blocks.map((b, i) => (
            <rect key={i} x={b.x} y={b.y} width={b.w} height={b.h} fill={i % 2 ? `url(#dotA-${id})` : inkA} />
          ))}
          <rect x={door.x + door.w * 0.3} y={door.y + door.h * 0.35} width={door.w * 0.4} height={door.h * 0.65} fill={dark} />
          <line x1="0" y1={top + 10} x2={W} y2={top + 10} stroke={dark} strokeWidth="4" />
          {/* 의자 하나 */}
          <path d={`M${mx - 20} ${top + 10} V${top - 30} M${mx - 20} ${top - 10} H${mx + 8} V${top + 10} M${mx + 8} ${top - 10} V${top + 10}`} stroke={dark} strokeWidth="4" fill="none" />
        </>
      ),
      b: (
        <>
          <circle cx={mx} cy={my} r={28 + r() * 16} fill={inkB} />
          <path d={`M${mx} ${my} L${mx + 160} ${top + 10} L${mx - 160} ${top + 10} Z`} fill={`url(#dot-${id})`} />
        </>
      ),
    };
  }

  // CLASSIC
  const arcR = 100 + r() * 30;
  const cx = 150;
  const staffY = 40 + r() * 30;
  const notes = Array.from({ length: 6 }, (_, i) => ({ x: 40 + i * 40 + r() * 10, y: staffY + Math.floor(r() * 9) * 4 }));
  return {
    a: (
      <>
        <path d={`M${cx - arcR} ${top} A${arcR} ${arcR} 0 0 1 ${cx + arcR} ${top}`} fill="none" stroke={inkA} strokeWidth="14" />
        <path d={`M${cx - arcR + 26} ${top} A${arcR - 26} ${arcR - 26} 0 0 1 ${cx + arcR - 26} ${top}`} fill="none" stroke={inkA} strokeWidth="3" />
        {Array.from({ length: 5 }, (_, i) => (
          <line key={i} x1="16" x2={W - 16} y1={staffY + i * 8} y2={staffY + i * 8} stroke={dark} strokeWidth="1.4" />
        ))}
        {notes.map((n, i) => (
          <g key={`n${i}`}>
            <ellipse cx={n.x} cy={n.y} rx="5.5" ry="4" fill={dark} transform={`rotate(-20 ${n.x} ${n.y})`} />
            <line x1={n.x + 5} x2={n.x + 5} y1={n.y} y2={n.y - 24} stroke={dark} strokeWidth="1.6" />
          </g>
        ))}
        {Array.from({ length: 9 }, (_, i) => (
          <line key={`s${i}`} x1={cx - 64 + i * 16} x2={cx - 64 + i * 16} y1={top - 70} y2={top} stroke={dark} strokeWidth="1.2" />
        ))}
      </>
    ),
    b: (
      <>
        <path d={`M${cx - arcR + 40} ${top} A${arcR - 40} ${arcR - 40} 0 0 1 ${cx + arcR - 40} ${top} Z`} fill={`url(#dot-${id})`} />
        <circle cx={cx + (r() - 0.5) * 120} cy={top - arcR - 10} r="18" fill={inkB} />
      </>
    ),
  };
}
