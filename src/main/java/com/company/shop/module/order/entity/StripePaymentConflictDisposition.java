package com.company.shop.module.order.entity;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "stripe_payment_conflict_dispositions")
public class StripePaymentConflictDisposition extends BaseEntity {

    @Column(name = "conflict_id", nullable = false, updatable = false)
    private UUID conflictId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, updatable = false, length = 32)
    private StripePaymentConflictDispositionType actionType;

    @Column(name = "actor_email", nullable = false, updatable = false, length = 255)
    private String actorEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StripePaymentConflictDisposition() {
    }

    private StripePaymentConflictDisposition(
            UUID conflictId, StripePaymentConflictDispositionType actionType, String actorEmail) {
        if (conflictId == null) {
            throw new IllegalArgumentException("Stripe payment conflict id is required");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("Stripe payment conflict disposition type is required");
        }
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new IllegalArgumentException("Stripe payment conflict disposition actor email is required");
        }
        this.conflictId = conflictId;
        this.actionType = actionType;
        this.actorEmail = actorEmail.trim();
        this.createdAt = Instant.now();
    }

    public static StripePaymentConflictDisposition record(
            UUID conflictId, StripePaymentConflictDispositionType actionType, String actorEmail) {
        return new StripePaymentConflictDisposition(conflictId, actionType, actorEmail);
    }

    public UUID getConflictId() { return conflictId; }
    public StripePaymentConflictDispositionType getActionType() { return actionType; }
    public String getActorEmail() { return actorEmail; }
    public Instant getCreatedAt() { return createdAt; }
}
