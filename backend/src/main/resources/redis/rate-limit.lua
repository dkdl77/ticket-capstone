-- KEYS[1] = rl:{bucket}:{ip}, ARGV[1] = 창 길이(ms)
-- 반환: { 현재 창의 요청 수, 창이 끝날 때까지 남은 ms }
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
  -- 만료가 빠진 키(이론상 없음)를 스스로 복구
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
  ttl = tonumber(ARGV[1])
end
return { count, ttl }
