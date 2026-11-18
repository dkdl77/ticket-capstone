package com.ticketing.queue;

/** Redis 키 이름을 한곳에서 정의한다. */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String waiting(long performanceId) {
        return "queue:" + performanceId + ":waiting";
    }

    public static String seen(long performanceId) {
        return "queue:" + performanceId + ":seen";
    }

    public static String active(long performanceId) {
        return "queue:" + performanceId + ":active";
    }

    public static String seq(long performanceId) {
        return "queue:" + performanceId + ":seq";
    }

    /** STRING tokenId — 이 사용자가 이 회차에서 가진 대기열 토큰(한 사용자 = 회차당 토큰 1개). */
    public static String userToken(long performanceId, long userId) {
        return "queue:" + performanceId + ":user:" + userId;
    }

    /** HASH { perf, uid, hold } */
    public static String token(String tokenId) {
        return "queue:token:" + tokenId;
    }

    /** HASH { token, perf, seats, total, expiresAt } */
    public static String hold(String holdId) {
        return "hold:" + holdId;
    }

    /** STRING holdId (TTL = 선점 시간) */
    public static String seatHold(long performanceId, long seatId) {
        return "seat:hold:" + performanceId + ":" + seatId;
    }
}
