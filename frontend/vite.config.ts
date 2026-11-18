import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, type PluginOption } from 'vite';
import react from '@vitejs/plugin-react';

// 모의 API는 `vite --mode mock`(npm run dev:mock)일 때만 개발 서버 미들웨어로 붙는다.
// vite.config.ts와 mock/ 폴더는 Node에서만 실행되므로 프로덕션 번들(dist)에 절대 들어가지 않는다.
// fontsource CSS는 woff2와 woff를 함께 적는다. woff2를 못 읽는 브라우저는 사실상 없으므로
// woff 줄을 지워 dist(=이미지) 크기를 약 40% 줄인다. 폰트가 없으면 시스템 한글 서체로 대체된다.
function woff2Only(): PluginOption {
  return {
    name: 'fontsource-woff2-only',
    enforce: 'pre',
    transform(code, id) {
      if (!id.includes('@fontsource') || !id.endsWith('.css')) return null;
      return { code: code.replace(/,\s*url\([^)]*\.woff\)\s*format\('woff'\)/g, ''), map: null };
    },
  };
}

// 직접 만든 포스터 이미지(public/posters/{공연 id}.webp, {공연 id}-sm.webp) 목록을 빌드 시점에 모듈로 만든다.
// 두 크기가 모두 있는 공연만 이미지를 쓰고, 나머지는 생성형 포스터를 그린다 → 없는 파일을 요청하는 404가 생기지 않는다.
// 개발 서버에서는 파일을 넣거나 지우면 전체 새로고침으로 목록이 다시 만들어진다.
function posterImages(): PluginOption {
  const VIRTUAL = 'virtual:poster-images';
  const RESOLVED = '\0' + VIRTUAL;
  const dir = fileURLToPath(new URL('./public/posters', import.meta.url));
  const ids = () => {
    let files: string[] = [];
    try {
      files = readdirSync(dir);
    } catch {
      return [];
    }
    const set = new Set(files);
    return files
      .map((f) => /^(\d+)\.webp$/.exec(f)?.[1])
      .filter((id): id is string => !!id && set.has(`${id}-sm.webp`))
      .map(Number)
      .sort((a, b) => a - b);
  };
  return {
    name: 'poster-images',
    resolveId: (id) => (id === VIRTUAL ? RESOLVED : null),
    load: (id) => (id === RESOLVED ? `export default ${JSON.stringify(ids())};` : null),
    configureServer(server) {
      server.watcher.add(dir);
      const reload = (file: string) => {
        if (!file.replace(/\\/g, '/').includes('/public/posters/')) return;
        const mod = server.moduleGraph.getModuleById(RESOLVED);
        if (mod) server.moduleGraph.invalidateModule(mod);
        server.ws.send({ type: 'full-reload' });
      };
      server.watcher.on('add', reload);
      server.watcher.on('unlink', reload);
    },
  };
}

export default defineConfig(async ({ command, mode }) => {
  const plugins: PluginOption[] = [react(), woff2Only(), posterImages()];
  if (command === 'serve' && mode === 'mock') {
    const { mockApiPlugin } = await import('./mock/server');
    plugins.push(mockApiPlugin());
  }

  return {
    plugins,
    preview: {
      port: 4173,
      strictPort: true,
      proxy: { '/api': { target: process.env.API_PROXY ?? 'http://localhost:8080', changeOrigin: false } },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: mode === 'mock' ? undefined : {
        // 기본은 로컬 백엔드(8080). 통합 환경 등 다른 곳에 붙일 때: API_PROXY=http://localhost:8088 npm run dev
        '/api': { target: process.env.API_PROXY ?? 'http://localhost:8080', changeOrigin: false },
      },
    },
    build: {
      target: 'es2022',
      sourcemap: false,
      // 폰트·이미지를 data: URI로 인라인하지 않는다 → CSP를 'self'만으로 유지
      assetsInlineLimit: 0,
      cssCodeSplit: true,
    },
  };
});
