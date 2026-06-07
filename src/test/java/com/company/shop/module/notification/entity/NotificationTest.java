package com.company.shop.module.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    void pending_shouldCreatePendingNotification() {
        UUID sourceEventId = UUID.randomUUID();

        Notification notification = pendingNotification(sourceEventId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(notification.getCreatedAt()).isNotNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    void markSent_shouldMarkNotificationSentClearLastErrorAndKeepAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markDeliveryAttemptFailed("temporary failure", 3);

        notification.markSent();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    void markDeliveryAttemptFailed_shouldKeepNotificationPendingWhenAttemptsRemainBelowMaxAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());

        notification.markDeliveryAttemptFailed("temporary failure", 3);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("temporary failure");
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    void markDeliveryAttemptFailed_shouldMarkNotificationFailedWhenAttemptsReachMaxAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markDeliveryAttemptFailed("first temporary failure", 2);

        notification.markDeliveryAttemptFailed("delivery failed", 2);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(2);
        assertThat(notification.getLastError()).isEqualTo("delivery failed");
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    void markFailed_shouldMarkNotificationFailedAndStoreLastError() {
        Notification notification = pendingNotification(UUID.randomUUID());

        notification.markFailed("delivery failed");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getLastError()).isEqualTo("delivery failed");
        assertThat(notification.getSentAt()).isNull();
    }

    private Notification pendingNotification(UUID sourceEventId) {
        return Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
    }
}
