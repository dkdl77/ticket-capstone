-- 입장 여부 확인(좌석 조회·선점·확정 때). 상태를 바꾸지 않는 가벼운 확인.
-- KEYS: 1 waiting 2 active   ARGV: 1 tokenId
-- 반환: {1, 0, untilMs} ADMITTED / {0, position, 0} WAITING / {-1} 무효
local now = now_ms()
local until_ms = redis.call('ZSCORE', KEYS[2], ARGV[1])
if until_ms and tonumber(until_ms) > now then
  return {1, 0, tonumber(until_ms)}
end
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
  return {0, rank, 0}
end
return {-1}
