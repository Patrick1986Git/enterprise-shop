package com.company.shop.module.order.outbox;

public enum OutboxEventProblemType {
    STALE_PENDING,
    STALE_FAILED,
    HIGH_ATTEMPT_FAILED,
    DEAD_LETTER
}
