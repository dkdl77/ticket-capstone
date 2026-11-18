package com.ticketing.reservation;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * 예매번호: SecureRandom, 12자. 혼동되는 0/O, 1/I/L 을 뺀 31자 알파벳 → 31^12 ≈ 7.9×10^17 가지.
 * 요청 제한(분당 20회)과 합치면 무차별 대입으로 남의 예매를 맞히는 것은 사실상 불가능하다.
 */
@Component
public class ReservationCodeGenerator {

    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }
}
