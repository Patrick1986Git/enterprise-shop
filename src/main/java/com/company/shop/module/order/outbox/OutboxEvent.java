package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxEventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "dead_letter_reason")
    private String deadLetterReason;

    @Column(name = "requeue_count", nullable = false)
    private int requeueCount;

    @Column(name = "last_requeued_at")
    private Instant lastRequeuedAt;

    @Column(name = "last_requeued_by", length = 255)
    private String lastRequeuedBy;

    protected OutboxEvent() {
    }

    private OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public static OutboxEvent pending(String aggregateType, UUID aggregateId, String eventType, String payload) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, payload);
    }

    public void markProcessed() {
        Instant now = Instant.now();
        this.status = OutboxEventStatus.PROCESSED;
        this.processedAt = now;
        this.lastAttemptAt = now;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.deadLetterReason = null;
    }

    public void markFailed(String errorMessage) {
        Instant now = Instant.now();
        this.status = OutboxEventStatus.FAILED;
        this.attempts += 1;
        this.lastAttemptAt = now;
        this.lastError = errorMessage;
        this.processedAt = null;
    }

    public void scheduleRetry(String errorMessage, Instant nextAttemptAt) {
        Instant now = Instant.now();
        this.status = OutboxEventStatus.PENDING;
        this.attempts += 1;
        this.lastAttemptAt = now;
        this.lastError = errorMessage;
        this.nextAttemptAt = nextAttemptAt;
        this.processedAt = null;
        this.deadLetterReason = null;
    }

    public void markDeadLetter(String errorMessage, String deadLetterReason) {
        this.status = OutboxEventStatus.DEAD_LETTER;
        this.attempts += 1;
        this.lastAttemptAt = Instant.now();
        this.lastError = errorMessage;
        this.deadLetterReason = deadLetterReason;
        this.nextAttemptAt = null;
        this.processedAt = null;
    }

    public void requeueForProcessing(String requeuedBy) {
        String normalizedRequeuedBy = requeuedBy == null ? null : requeuedBy.trim();
        if (normalizedRequeuedBy == null || normalizedRequeuedBy.isBlank()) {
            throw new IllegalArgumentException("requeuedBy must not be blank");
        }

        this.status = OutboxEventStatus.PENDING;
        this.processedAt = null;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.deadLetterReason = null;
        this.requeueCount += 1;
        this.lastRequeuedAt = Instant.now();
        this.lastRequeuedBy = normalizedRequeuedBy;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxEventStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getDeadLetterReason() {
        return deadLetterReason;
    }

    public int getRequeueCount() {
        return requeueCount;
    }

    public Instant getLastRequeuedAt() {
        return lastRequeuedAt;
    }

    public String getLastRequeuedBy() {
        return lastRequeuedBy;
    }
}
