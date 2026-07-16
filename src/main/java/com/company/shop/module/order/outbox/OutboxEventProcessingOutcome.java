package com.company.shop.module.order.outbox;

enum OutboxEventProcessingOutcome {
    PROCESSED,
    FAILED,
    SKIPPED
}
