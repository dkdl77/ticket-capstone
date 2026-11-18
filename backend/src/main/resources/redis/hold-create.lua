-- 좌석 임시 선점: 전부 아니면 전무.
-- KEYS: 1 token(HASH) 2 새 hold(HASH) 3 이전 hold(HASH, 없으면 더미 키)
--       4 .. 3+n        새로 잡을 좌석 키 (seat:hold:{perf}:{seatId})
--       4+n .. #KEYS    이전 선점의 좌석 키
-- ARGV: 1 newHoldId 2 prevHoldId('' = 없음) 3 holdMs 4 n 5 seatIdsCsv 6 totalPrice 7 perfId 8 tokenId 9 notAfterMs(입장 만료) 10 userId
-- 반환: {1, expiresAtMs} 성공 / {0, i, j, ...} i번째(1부터) 좌석이 이미 선점됨 / {-1} 이전 선점이 그사이 바뀜(호출자가 재시도) / {-2} 입장 만료 임박
--
-- Redis는 스크립트 하나를 통째로 실행하므로 "모두 비었는지 확인 → 전부 SET"이 원자적이다.
-- (하나씩 SET NX 하고 실패 시 되돌리는 방식과 결과가 같고, 되돌릴 일이 없다.)
local cur = redis.call('HGET', KEYS[1], 'hold') or ''
if cur ~= ARGV[2] then
  return {-1}
end

local n = tonumber(ARGV[4])
local prev = ARGV[2]
local taken = {}
for i = 1, n do
  local v = redis.call('GET', KEYS[3 + i])
  -- 내 이전 선점이 잡고 있는 좌석은 비어 있는 것으로 본다(좌석을 바꿔 다시 고를 때)
  if v and v ~= prev then
    taken[#taken + 1] = i
  end
end
if #taken > 0 then
  local out = {0}
  for _, i in ipairs(taken) do out[#out + 1] = i end
  return out
end

-- 이전 선점 해제(아직 내 것인 좌석 키만)
if prev ~= '' then
  for i = 4 + n, #KEYS do
    if redis.call('GET', KEYS[i]) == prev then
      redis.call('DEL', KEYS[i])
    end
  end
  redis.call('DEL', KEYS[3])
end

-- 만료 = min(지금 + HOLD, 입장 만료). TTL은 고정이며 연장 API가 없다.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local expires = now + tonumber(ARGV[3])
local not_after = tonumber(ARGV[9])
if not_after > 0 and not_after < expires then
  expires = not_after
end
local ttl = expires - now
if ttl < 1000 then
  return {-2}  -- 입장 시간이 거의 끝났다
end

for i = 1, n do
  redis.call('SET', KEYS[3 + i], ARGV[1], 'PX', ttl)
end
redis.call('HSET', KEYS[2], 'token', ARGV[8], 'uid', ARGV[10], 'perf', ARGV[7], 'seats', ARGV[5], 'total', ARGV[6], 'expiresAt', expires)
redis.call('PEXPIRE', KEYS[2], ttl)
redis.call('HSET', KEYS[1], 'hold', ARGV[1])
return {1, expires}
