package com.ticketing.ratelimit;

import java.time.Duration;

public interface RateLimiter {

    /** 요청 1회를 기록하고 허용 여부를 돌려준다. */
    Decision hit(String bucket, String clientKey, int limit, Duration window);

    record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }
}
