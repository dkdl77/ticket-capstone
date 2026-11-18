import { useState } from 'react';
import imageIds from 'virtual:poster-images';
import type { Genre, Poster as PosterSpec } from '../api';
import { Poster } from './Poster';

/**
 * 공연 포스터 자리. 직접 만든 이미지가 있으면 이미지를, 없으면 생성형 포스터를 그린다.
 * - 어떤 공연에 이미지가 있는지는 빌드 시점 목록(virtual:poster-images)으로 안다 → 없는 파일을 요청하지 않는다(404 없음).
 * - 목록에 있는데도 불러오지 못하면(배포 누락·네트워크) onError에서 생성형 포스터로 바꾼다 → 빈 칸이 남지 않는다.
 * - 그림에는 글자가 없다. 제목은 포스터 밖(카드 아래, 상세의 h1)에 HTML 글자로 한 번만 둔다.
 *   생성형 포스터도 같은 규칙으로 글자 없이(bare) 그린다.
 * - 이미지는 3:4(작은 것 448×600, 큰 것 896×1200). width/height를 주어 불러오기 전에도 자리를 잡는다.
 */
const HAS_IMAGE = new Set(imageIds);

type Props = {
  showId: number;
  poster: PosterSpec;
  title: string;
  genre: Genre;
  /** sm: 카드·랭킹·대기 화면, lg: 공연 상세 */
  size?: 'sm' | 'lg';
  /**
   * 포스터만 따로 있을 때(상세 화면) 쓰는 대체 텍스트. 같은 링크 안에 제목 글자가 있는 카드에서는 생략해
   * 화면 낭독기가 제목을 두 번 읽지 않게 한다(장식 이미지로 처리).
   */
  alt?: string;
  /** 첫 화면에 바로 보이는 큰 포스터는 지연 로드하지 않는다 */
  eager?: boolean;
};

export function PosterArt({ showId, poster, title, genre, size = 'sm', alt, eager = false }: Props) {
  const [failed, setFailed] = useState(false);

  if (!HAS_IMAGE.has(showId) || failed) {
    return <Poster poster={poster} title={title} genre={genre} bare label={alt} className="poster--art" />;
  }

  const sm = size === 'sm';
  return (
    <img
      className="poster poster--art poster--img"
      src={`/posters/${showId}${sm ? '-sm' : ''}.webp`}
      width={sm ? 448 : 896}
      height={sm ? 600 : 1200}
      alt={alt ?? ''}
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
      fetchPriority={eager ? 'high' : undefined}
      draggable={false}
      onError={() => setFailed(true)}
    />
  );
}
