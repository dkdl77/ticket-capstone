/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 이미지 빌드 때 넣는 버전(Dockerfile의 APP_VERSION). 개발 서버에서는 없다 */
  readonly VITE_APP_VERSION?: string;
}

/** vite.config.ts의 posterImages 플러그인이 만든다: 포스터 이미지(두 크기 모두)가 있는 공연 id 목록 */
declare module 'virtual:poster-images' {
  const ids: number[];
  export default ids;
}
