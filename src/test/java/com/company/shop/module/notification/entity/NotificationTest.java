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
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    void markSent_shouldMarkNotificationSentAndClearLastError() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markFailed("temporary failure");

        notification.markSent();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    void markFailed_shouldMarkNotificationFailedAndStoreLastError() {
        Notification notification = pendingNotification(UUID.randomUUID());

        notification.markFailed("delivery failed");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
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
