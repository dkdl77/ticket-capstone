package com.ticketing.common;

import java.util.function.Supplier;

/**
 * 트랜잭션 경계를 서비스 계층이 명시적으로 잡기 위한 작은 인터페이스.
 * 운영에서는 Spring TransactionTemplate, 단위 테스트에서는 그냥 실행하는 가짜 구현을 쓴다.
 * 작업 안에서 RuntimeException이 나면 롤백한다.
 */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
