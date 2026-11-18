package com.ticketing.auth;

import java.util.Optional;

public interface UserRepository {

    record UserRow(long id, String email, String name, String passwordHash, boolean loginEnabled) {
    }

    /** 생성된 id. 이메일이 이미 있으면 DuplicateKeyException(유니크 제약 = 가입 경쟁의 최종 방어선). */
    long insert(String email, String passwordHash, String name);

    Optional<UserRow> findByEmail(String normalizedEmail);

    Optional<UserRow> findById(long id);

    /** 시드 전용: 있으면 비밀번호·이름을 바꾸고 로그인 가능으로, 없으면 만든다. */
    void upsertDemo(String email, String passwordHash, String name);
}
