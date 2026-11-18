-- 대기열 진입
-- KEYS: 1 waiting(ZSET 순번) 2 seen(ZSET 마지막 폴링 ms) 3 active(ZSET 입장 만료 ms) 4 seq(카운터) 5 token(HASH)
--       6 user(STRING: 이 사용자가 이 회차에서 가진 tokenId)
-- ARGV: 1 maxActive 2 admitMs 3 waitTtlMs 4 tokenId 5 performanceId 6 tokenTtlMs 7 userId
-- 반환: {1, 0, admittedUntilMs} = ADMITTED, {0, position, 0} = WAITING
local now = now_ms()
local max_active = tonumber(ARGV[1])
local admit_ms = tonumber(ARGV[2])
advance(KEYS[1], KEYS[2], KEYS[3], now, max_active, admit_ms, tonumber(ARGV[3]))

-- 토큰은 발급받은 사용자에게 묶인다(v3)
redis.call('HSET', KEYS[5], 'perf', ARGV[5], 'uid', ARGV[7])
redis.call('PEXPIRE', KEYS[5], ARGV[6])
redis.call('SET', KEYS[6], ARGV[4], 'PX', ARGV[6])

-- advance 직후 빈자리가 있으면 대기자는 0명이다 → 새로 온 사람이 새치기하는 일이 없다.
if redis.call('ZCARD', KEYS[3]) < max_active then
  local until_ms = now + admit_ms
  redis.call('ZADD', KEYS[3], until_ms, ARGV[4])
  return {1, 0, until_ms}
end
local seq = redis.call('INCR', KEYS[4])
redis.call('ZADD', KEYS[1], seq, ARGV[4])
redis.call('ZADD', KEYS[2], now, ARGV[4])
return {0, redis.call('ZRANK', KEYS[1], ARGV[4]), 0}
