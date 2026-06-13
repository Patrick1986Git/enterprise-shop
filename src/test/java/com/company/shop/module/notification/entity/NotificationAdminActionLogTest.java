package com.company.shop.module.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NotificationAdminActionLogTest {

    @Test
    void requeue_shouldCreateRequeueActionLog() {
        UUID notificationId = UUID.randomUUID();
        Instant beforeCreate = Instant.now();

        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(notificationId, "admin@example.com");

        assertThat(log.getNotificationId()).isEqualTo(notificationId);
        assertThat(log.getActionType()).isEqualTo(NotificationAdminActionType.REQUEUE);
        assertThat(log.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(log.getCreatedAt()).isNotNull().isAfterOrEqualTo(beforeCreate);
        assertThat(log.getDetails()).isNull();
    }

    @Test
    void requeue_shouldTrimActorEmail() {
        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(
                UUID.randomUUID(),
                "  admin@example.com  ");

        assertThat(log.getActorEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void requeue_shouldRejectNullNotificationId() {
        assertThatThrownBy(() -> NotificationAdminActionLog.requeue(null, "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification id is required");
    }

    @Test
    void requeue_shouldRejectBlankActorEmail() {
        assertThatThrownBy(() -> NotificationAdminActionLog.requeue(UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification admin action actor email is required");
    }
}
