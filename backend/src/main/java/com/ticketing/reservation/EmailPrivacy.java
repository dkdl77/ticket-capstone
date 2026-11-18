package com.ticketing.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 예매(reservations) 테이블에는 이메일 원문을 두지 않는다(데이터 최소화). 원문은 로그인에 필요한 users 테이블에만 있다.
 * - 해시: 정규화(trim+소문자)한 이메일의 SHA-256(로그인 잠금 키 등)
 * - 화면 표시용: 마스킹 문자열(ab***@example.com)
 */
public final class EmailPrivacy {

    private EmailPrivacy() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String hash(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(email).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String mask(String email) {
        String normalized = normalize(email);
        int at = normalized.lastIndexOf('@');
        String local = normalized.substring(0, at);
        String domain = normalized.substring(at + 1);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***@" + domain;
    }
}
