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
        Instant processedFrom,
        Instant processedTo,
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
            Boolean requeuedOnly,
            OutboxEventProblemType problemType) {
        this(status, aggregateType, aggregateId, eventType, lastErrorContains, createdFrom, createdTo, null, null,
                lastAttemptFrom, lastAttemptTo, attemptsMin, attemptsMax, requeuedOnly, problemType);
    }

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
        this(status, aggregateType, aggregateId, eventType, lastErrorContains, createdFrom, createdTo, null, null,
                lastAttemptFrom, lastAttemptTo, attemptsMin, attemptsMax, requeuedOnly, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private OutboxEventStatus status;
        private String aggregateType;
        private UUID aggregateId;
        private String eventType;
        private String lastErrorContains;
        private Instant createdFrom;
        private Instant createdTo;
        private Instant processedFrom;
        private Instant processedTo;
        private Instant lastAttemptFrom;
        private Instant lastAttemptTo;
        private Integer attemptsMin;
        private Integer attemptsMax;
        private Boolean requeuedOnly;
        private OutboxEventProblemType problemType;

        private Builder() {
        }

        public Builder status(OutboxEventStatus status) {
            this.status = status;
            return this;
        }

        public Builder aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder aggregateId(UUID aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder lastErrorContains(String lastErrorContains) {
            this.lastErrorContains = lastErrorContains;
            return this;
        }

        public Builder createdFrom(Instant createdFrom) {
            this.createdFrom = createdFrom;
            return this;
        }

        public Builder createdTo(Instant createdTo) {
            this.createdTo = createdTo;
            return this;
        }

        public Builder processedFrom(Instant processedFrom) {
            this.processedFrom = processedFrom;
            return this;
        }

        public Builder processedTo(Instant processedTo) {
            this.processedTo = processedTo;
            return this;
        }

        public Builder lastAttemptFrom(Instant lastAttemptFrom) {
            this.lastAttemptFrom = lastAttemptFrom;
            return this;
        }

        public Builder lastAttemptTo(Instant lastAttemptTo) {
            this.lastAttemptTo = lastAttemptTo;
            return this;
        }

        public Builder attemptsMin(Integer attemptsMin) {
            this.attemptsMin = attemptsMin;
            return this;
        }

        public Builder attemptsMax(Integer attemptsMax) {
            this.attemptsMax = attemptsMax;
            return this;
        }

        public Builder requeuedOnly(Boolean requeuedOnly) {
            this.requeuedOnly = requeuedOnly;
            return this;
        }

        public Builder problemType(OutboxEventProblemType problemType) {
            this.problemType = problemType;
            return this;
        }

        public OutboxEventAdminSearchCriteria build() {
            return new OutboxEventAdminSearchCriteria(
                    status,
                    aggregateType,
                    aggregateId,
                    eventType,
                    lastErrorContains,
                    createdFrom,
                    createdTo,
                    processedFrom,
                    processedTo,
                    lastAttemptFrom,
                    lastAttemptTo,
                    attemptsMin,
                    attemptsMax,
                    requeuedOnly,
                    problemType);
        }
    }
}
