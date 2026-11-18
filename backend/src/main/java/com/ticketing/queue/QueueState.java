package com.ticketing.queue;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 계약 4.1 QueueState. queueToken은 enter 응답에만 포함(status 응답에서는 필드 자체가 없다). */
public record QueueState(
        @JsonInclude(JsonInclude.Include.NON_NULL) String queueToken,
        QueueStore.State status,
        long position,
        Instant admittedUntil,
        int pollAfterSeconds) {
}
