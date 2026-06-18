package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event_admin_action_logs")
public class OutboxEventAdminActionLog extends BaseEntity {

    @Column(name = "outbox_event_id", nullable = false)
    private UUID outboxEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private OutboxEventAdminActionType actionType;

    @Column(name = "actor_email", nullable = false, length = 255)
    private String actorEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    protected OutboxEventAdminActionLog() {
    }

    private OutboxEventAdminActionLog(UUID outboxEventId, OutboxEventAdminActionType actionType, String actorEmail) {
        this.outboxEventId = requireNonNull(outboxEventId, "Outbox event id is required");
        this.actionType = requireNonNull(actionType, "Outbox event admin action type is required");
        this.actorEmail = requireText(actorEmail, "Outbox event admin action actor email is required").trim();
        this.createdAt = Instant.now();
    }

    public static OutboxEventAdminActionLog requeue(UUID outboxEventId, String actorEmail) {
        return new OutboxEventAdminActionLog(outboxEventId, OutboxEventAdminActionType.REQUEUE, actorEmail);
    }

    public UUID getOutboxEventId() {
        return outboxEventId;
    }

    public OutboxEventAdminActionType getActionType() {
        return actionType;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getDetails() {
        return details;
    }

    private static <T> T requireNonNull(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
