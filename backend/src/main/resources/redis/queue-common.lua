-- 대기열 스크립트 공통 부분(각 스크립트 앞에 붙여서 로드한다).
-- 시각은 앱 서버가 아니라 Redis의 TIME을 쓴다 → 복제본마다 시계가 조금 달라도 결과가 같다.
local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

-- 상태 전진: ① 만료된 입장자 정리 → ② 폴링이 끊긴 대기자 제거 → ③ 빈자리만큼 대기자를 들어온 순서대로 승격.
-- 스케줄러 없이, 요청이 들어올 때마다 이 함수가 원자적으로 실행된다.
local function advance(waiting, seen, active, now, max_active, admit_ms, wait_ttl_ms)
  redis.call('ZREMRANGEBYSCORE', active, '-inf', now)
  local stale = redis.call('ZRANGEBYSCORE', seen, '-inf', now - wait_ttl_ms)
  for _, m in ipairs(stale) do
    redis.call('ZREM', waiting, m)
    redis.call('ZREM', seen, m)
  end
  local free = max_active - redis.call('ZCARD', active)
  if free > 0 then
    local nexts = redis.call('ZRANGE', waiting, 0, free - 1)
    for _, m in ipairs(nexts) do
      redis.call('ZADD', active, now + admit_ms, m)
      redis.call('ZREM', waiting, m)
      redis.call('ZREM', seen, m)
    end
  end
end
