package com.ticketing.common;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * READ COMMITTED: 이벤트 행을 FOR UPDATE로 잠근 뒤 읽는 판매 합계가 "잠금 이후 최신 커밋"을 보도록 한다.
 * (MySQL 기본값 REPEATABLE READ에서는 스냅샷이 잠금 대기 전에 잡혀 오래된 합계를 볼 수 있다.)
 */
@Component
public class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    public SpringTransactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.template.setTimeout(5);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
