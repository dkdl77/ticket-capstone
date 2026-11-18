package com.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class EmailPrivacyAndCodeTest {

    @Test
    void 이메일_마스킹() {
        assertThat(EmailPrivacy.mask("abcdef@example.com")).isEqualTo("ab***@example.com");
        assertThat(EmailPrivacy.mask("Ab@Example.com")).isEqualTo("a***@example.com");
        assertThat(EmailPrivacy.mask("x@test.io")).isEqualTo("x***@test.io");
    }

    @Test
    void 이메일_해시는_대소문자와_공백을_무시하고_원문을_포함하지_않는다() {
        String h = EmailPrivacy.hash(" User@Example.COM ");
        assertThat(h).isEqualTo(EmailPrivacy.hash("user@example.com")).hasSize(64).doesNotContain("user");
    }

    @Test
    void 예매번호는_12자이고_혼동_문자가_없으며_겹치지_않는다() {
        ReservationCodeGenerator gen = new ReservationCodeGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = gen.next();
            assertThat(code).hasSize(12).doesNotContainPattern("[01OIL]");
            seen.add(code);
        }
        assertThat(seen).hasSize(10_000);
    }
}
