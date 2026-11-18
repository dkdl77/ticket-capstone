-- 로그인 실패 기록. KEYS[1] = auth:fail:{sha256(email)}, ARGV[1] = 잠금 기준 횟수, ARGV[2] = 잠금 시간(ms)
-- 실패할 때마다 만료를 다시 잡는다(마지막 실패로부터 잠금 시간 동안 기억). 기준 횟수에 닿으면 그때부터 잠금 시간 동안 잠긴다.
-- 반환: {횟수, 남은 ms}
local count = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return { count, redis.call('PTTL', KEYS[1]) }
