package com.company.shop.module.notification.entity;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "type", nullable = false, length = 80)
    private String type;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private NotificationStatus status;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "requeue_count", nullable = false)
    private int requeueCount;

    @Column(name = "last_requeued_at")
    private Instant lastRequeuedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    protected Notification() {
    }

    private Notification(String type, String recipient, String subject, String body, UUID sourceEventId) {
        this.type = requireText(type, "Notification type is required");
        this.recipient = requireText(recipient, "Notification recipient is required");
        this.subject = requireText(subject, "Notification subject is required");
        this.body = requireText(body, "Notification body is required");
        this.status = NotificationStatus.PENDING;
        this.sourceEventId = sourceEventId;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public static Notification pending(String type, String recipient, String subject, String body, UUID sourceEventId) {
        return new Notification(type, recipient, subject, body, sourceEventId);
    }

    public void markSent() {
        Instant now = Instant.now();
        this.status = NotificationStatus.SENT;
        this.sentAt = now;
        this.lastAttemptAt = now;
        this.lastError = null;
        this.nextAttemptAt = null;
    }

    public void markFailed(String errorMessage) {
        this.status = NotificationStatus.FAILED;
        this.lastError = errorMessage;
        this.sentAt = null;
        this.nextAttemptAt = null;
        this.lastAttemptAt = Instant.now();
    }

    public void markDeliveryAttemptFailed(String errorMessage, int maxAttempts, Instant nextAttemptAt) {
        this.attempts += 1;
        this.lastAttemptAt = Instant.now();
        this.lastError = errorMessage;
        this.sentAt = null;
        if (this.attempts >= maxAttempts) {
            this.status = NotificationStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.status = NotificationStatus.PENDING;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public void requeueForDelivery() {
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.lastError = null;
        this.sentAt = null;
        this.nextAttemptAt = null;
        this.lastAttemptAt = null;
        this.requeueCount += 1;
        this.lastRequeuedAt = Instant.now();
    }

    public String getType() {
        return type;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getRequeueCount() {
        return requeueCount;
    }

    public Instant getLastRequeuedAt() {
        return lastRequeuedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
