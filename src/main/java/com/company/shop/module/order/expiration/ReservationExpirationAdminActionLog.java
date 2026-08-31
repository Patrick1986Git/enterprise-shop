package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation_expiration_admin_action_logs")
public class ReservationExpirationAdminActionLog extends BaseEntity {
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "work_id", nullable = false)
    private UUID workId;
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private ReservationExpirationAdminActionType actionType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationExpirationAdminActionOutcome outcome;
    @Column(name = "actor_email", nullable = false, length = 255)
    private String actorEmail;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReservationExpirationAdminActionLog() {}

    private ReservationExpirationAdminActionLog(UUID orderId, UUID workId,
            ReservationExpirationAdminActionType actionType,
            ReservationExpirationAdminActionOutcome outcome, String actorEmail, Instant createdAt) {
        this.orderId = requireNonNull(orderId, "Order id is required");
        this.workId = requireNonNull(workId, "Reservation expiration work id is required");
        this.actionType = requireNonNull(actionType, "Reservation expiration admin action type is required");
        this.outcome = requireNonNull(outcome, "Reservation expiration admin action outcome is required");
        this.actorEmail = requireText(actorEmail, "Reservation expiration admin action actor email is required").trim();
        this.createdAt = requireNonNull(createdAt, "Reservation expiration admin action timestamp is required");
    }

    public static ReservationExpirationAdminActionLog adoption(
            UUID orderId, UUID workId, String actorEmail, Instant createdAt) {
        return new ReservationExpirationAdminActionLog(orderId, workId,
                ReservationExpirationAdminActionType.LEGACY_ADOPTION,
                ReservationExpirationAdminActionOutcome.ADOPTED, actorEmail, createdAt);
    }

    public static ReservationExpirationAdminActionLog recovery(UUID orderId, UUID workId,
            ReservationExpirationAdminActionOutcome outcome, String actorEmail, Instant createdAt) {
        if (outcome != ReservationExpirationAdminActionOutcome.REQUEUED
                && outcome != ReservationExpirationAdminActionOutcome.TERMINAL_NOOP) {
            throw new IllegalArgumentException("Recovery outcome must describe a recovery result");
        }
        return new ReservationExpirationAdminActionLog(orderId, workId,
                ReservationExpirationAdminActionType.RECOVERY, outcome, actorEmail, createdAt);
    }

    public UUID getOrderId() { return orderId; }
    public UUID getWorkId() { return workId; }
    public ReservationExpirationAdminActionType getActionType() { return actionType; }
    public ReservationExpirationAdminActionOutcome getOutcome() { return outcome; }
    public String getActorEmail() { return actorEmail; }
    public Instant getCreatedAt() { return createdAt; }

    private static <T> T requireNonNull(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }
}
