import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <div className="empty">
      <h1 className="empty__title" tabIndex={-1}>없는 페이지입니다</h1>
      <p>주소를 다시 확인하거나 공연 일정에서 시작해 주세요.</p>
      <Link className="btn" to="/">공연 일정 보기</Link>
    </div>
  );
}
