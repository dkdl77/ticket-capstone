-- 대기 상태 조회(폴링). 내 폴링 시각을 갱신하고 상태를 전진시킨 뒤 내 상태를 돌려준다.
-- KEYS: 1 waiting 2 seen 3 active 4 token 5 user(이 사용자의 tokenId)
-- ARGV: 1 maxActive 2 admitMs 3 waitTtlMs 4 tokenId 5 tokenTtlMs
-- 반환: {1, 0, untilMs} ADMITTED / {0, position, 0} WAITING / {-1} 토큰 무효(만료·이탈·없음)
local now = now_ms()
local wait_ttl_ms = tonumber(ARGV[3])

local seen = redis.call('ZSCORE', KEYS[2], ARGV[4])
if seen then
  if tonumber(seen) < now - wait_ttl_ms then
    -- 폴링이 너무 오래 끊겼다 → 대기열에서 빠진 것으로 본다
    redis.call('ZREM', KEYS[1], ARGV[4])
    redis.call('ZREM', KEYS[2], ARGV[4])
  else
    redis.call('ZADD', KEYS[2], now, ARGV[4])
  end
end

advance(KEYS[1], KEYS[2], KEYS[3], now, tonumber(ARGV[1]), tonumber(ARGV[2]), wait_ttl_ms)

local until_ms = redis.call('ZSCORE', KEYS[3], ARGV[4])
if until_ms then
  redis.call('PEXPIRE', KEYS[4], ARGV[5])
  redis.call('PEXPIRE', KEYS[5], ARGV[5])
  return {1, 0, tonumber(until_ms)}
end
local rank = redis.call('ZRANK', KEYS[1], ARGV[4])
if rank then
  redis.call('PEXPIRE', KEYS[4], ARGV[5])
  redis.call('PEXPIRE', KEYS[5], ARGV[5])
  return {0, rank, 0}
end
return {-1}
