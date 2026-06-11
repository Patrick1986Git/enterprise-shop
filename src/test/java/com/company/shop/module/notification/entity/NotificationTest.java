package com.company.shop.module.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
        assertThat(notification.getLastAttemptAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void markSent_shouldMarkNotificationSentClearLastErrorAndNextAttemptAtAndKeepAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markDeliveryAttemptFailed("temporary failure", 3, Instant.now().plusSeconds(60));

        notification.markSent();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getLastAttemptAt()).isEqualTo(notification.getSentAt());
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void markDeliveryAttemptFailed_shouldKeepNotificationPendingAndSetNextAttemptAtWhenAttemptsRemainBelowMaxAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());
        Instant nextAttemptAt = Instant.now().plusSeconds(60);

        notification.markDeliveryAttemptFailed("temporary failure", 3, nextAttemptAt);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("temporary failure");
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    @Test
    void markDeliveryAttemptFailed_shouldMarkNotificationFailedAndClearNextAttemptAtWhenAttemptsReachMaxAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markDeliveryAttemptFailed("first temporary failure", 2, Instant.now().plusSeconds(60));

        notification.markDeliveryAttemptFailed("delivery failed", 2, Instant.now().plusSeconds(60));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(2);
        assertThat(notification.getLastError()).isEqualTo("delivery failed");
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void markFailed_shouldMarkNotificationFailedStoreLastErrorAndClearNextAttemptAt() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markDeliveryAttemptFailed("temporary failure", 3, Instant.now().plusSeconds(60));

        notification.markFailed("delivery failed");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("delivery failed");
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void requeueForDelivery_shouldResetFailedNotificationForImmediateDelivery() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markDeliveryAttemptFailed("first temporary failure", 2, Instant.now().plusSeconds(60));
        notification.markDeliveryAttemptFailed("delivery failed", 2, Instant.now().plusSeconds(60));

        notification.requeueForDelivery();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getLastAttemptAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
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
