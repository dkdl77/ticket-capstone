package com.ticketing.queue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 대기열 토큰: SecureRandom 256비트 → base64url 43자. 선점 ID: 128비트 → 22자.
 * Redis에는 토큰 원문 대신 SHA-256(tokenId)을 저장한다 → Redis 내용이 유출돼도 토큰을 재사용할 수 없다.
 */
@Component
public class QueueTokens {

    public static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    public static final Pattern HOLD_ID_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{22}$");

    private final SecureRandom random = new SecureRandom();

    public String newToken() {
        return random(32);
    }

    public String newHoldId() {
        return random(16);
    }

    private String random(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String tokenId(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
