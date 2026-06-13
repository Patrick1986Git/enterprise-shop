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
@Table(name = "notification_admin_action_logs")
public class NotificationAdminActionLog extends BaseEntity {

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private NotificationAdminActionType actionType;

    @Column(name = "actor_email", nullable = false, length = 255)
    private String actorEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    protected NotificationAdminActionLog() {
    }

    private NotificationAdminActionLog(UUID notificationId, NotificationAdminActionType actionType, String actorEmail) {
        this.notificationId = requireNonNull(notificationId, "Notification id is required");
        this.actionType = requireNonNull(actionType, "Notification admin action type is required");
        this.actorEmail = requireText(actorEmail, "Notification admin action actor email is required").trim();
        this.createdAt = Instant.now();
    }

    public static NotificationAdminActionLog requeue(UUID notificationId, String actorEmail) {
        return new NotificationAdminActionLog(notificationId, NotificationAdminActionType.REQUEUE, actorEmail);
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public NotificationAdminActionType getActionType() {
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
