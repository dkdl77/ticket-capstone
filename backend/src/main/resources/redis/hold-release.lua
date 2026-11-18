-- 선점 해제. 선점한 토큰만 해제할 수 있다.
-- KEYS: 1 hold(HASH) 2 token(HASH) 3.. 좌석 키
-- ARGV: 1 holdId 2 tokenId
-- 반환: 1 해제함 / 0 없거나 내 것이 아님
if redis.call('HGET', KEYS[1], 'token') ~= ARGV[2] then
  return 0
end
for i = 3, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then
    redis.call('DEL', KEYS[i])
  end
end
redis.call('DEL', KEYS[1])
if redis.call('HGET', KEYS[2], 'hold') == ARGV[1] then
  redis.call('HDEL', KEYS[2], 'hold')
end
return 1
