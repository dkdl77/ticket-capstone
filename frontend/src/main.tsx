import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';

// 서체는 npm 패키지로 번들에 포함한다(외부 CDN 없음). 두 서체 모두 SIL Open Font License 1.1.
// 한글 서체는 유니코드 범위별로 쪼개져 있어 화면에 쓰인 글자 범위의 파일만 내려받는다.
import '@fontsource/ibm-plex-sans-kr/400.css';
import '@fontsource/ibm-plex-sans-kr/600.css';
import '@fontsource/black-han-sans/400.css';
import './styles.css';

import { App } from './App';
import { AuthProvider } from './auth';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
