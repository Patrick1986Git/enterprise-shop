package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventAdminSearchCriteria(
        OutboxEventStatus status,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String lastErrorContains,
        Instant createdFrom,
        Instant createdTo,
        Instant lastAttemptFrom,
        Instant lastAttemptTo,
        Integer attemptsMin,
        Integer attemptsMax,
        Boolean requeuedOnly,
        OutboxEventProblemType problemType) {

    public OutboxEventAdminSearchCriteria(
            OutboxEventStatus status,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String lastErrorContains,
            Instant createdFrom,
            Instant createdTo,
            Instant lastAttemptFrom,
            Instant lastAttemptTo,
            Integer attemptsMin,
            Integer attemptsMax,
            Boolean requeuedOnly) {
        this(status, aggregateType, aggregateId, eventType, lastErrorContains, createdFrom, createdTo,
                lastAttemptFrom, lastAttemptTo, attemptsMin, attemptsMax, requeuedOnly, null);
    }
}
