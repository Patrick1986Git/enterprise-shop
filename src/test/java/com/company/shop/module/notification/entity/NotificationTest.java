package com.company.shop.module.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(notification.getRequeueCount()).isZero();
        assertThat(notification.getLastRequeuedAt()).isNull();
        assertThat(notification.getLastRequeuedBy()).isNull();
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

        Instant beforeRequeue = Instant.now();

        notification.requeueForDelivery("admin@example.com");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getRequeueCount()).isEqualTo(1);
        assertThat(notification.getLastRequeuedAt()).isNotNull();
        assertThat(notification.getLastRequeuedAt()).isAfterOrEqualTo(beforeRequeue);
        assertThat(notification.getLastRequeuedBy()).isEqualTo("admin@example.com");
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getLastAttemptAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void requeueForDelivery_shouldIncrementRequeueCountAcrossMultipleRequeues() {
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markFailed("delivery failed");

        notification.requeueForDelivery("first-admin@example.com");
        Instant firstRequeuedAt = notification.getLastRequeuedAt();
        notification.markFailed("delivery failed again");
        notification.requeueForDelivery("second-admin@example.com");

        assertThat(notification.getRequeueCount()).isEqualTo(2);
        assertThat(notification.getLastRequeuedAt()).isNotNull();
        assertThat(notification.getLastRequeuedAt()).isAfterOrEqualTo(firstRequeuedAt);
        assertThat(notification.getLastRequeuedBy()).isEqualTo("second-admin@example.com");
    }

    @Test
    void claim_shouldEstablishOwnershipAndCountAttemptExactlyOnce() {
        Notification notification = pendingNotification(UUID.randomUUID());
        UUID token = UUID.randomUUID();
        Instant claimedAt = Instant.now();
        Instant expiresAt = claimedAt.plusSeconds(60);

        notification.claim(token, claimedAt, expiresAt);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getClaimToken()).isEqualTo(token);
        assertThat(notification.getClaimExpiresAt()).isEqualTo(expiresAt);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastAttemptAt()).isEqualTo(claimedAt);
        assertThatThrownBy(() -> notification.claim(UUID.randomUUID(), claimedAt, expiresAt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalizeSent_shouldRequireOwnerAndClearClaim() {
        Notification notification = pendingNotification(UUID.randomUUID());
        UUID token = claim(notification);

        assertThat(notification.finalizeSent(UUID.randomUUID())).isFalse();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.finalizeSent(token)).isTrue();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getClaimToken()).isNull();
        assertThat(notification.getClaimExpiresAt()).isNull();
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void finalizeFailed_shouldScheduleRetryWithoutCountingAttemptTwice() {
        Notification notification = pendingNotification(UUID.randomUUID());
        UUID token = claim(notification);
        Instant nextAttemptAt = Instant.now().plusSeconds(60);

        assertThat(notification.finalizeFailed(token, "sender failed", 3, nextAttemptAt)).isTrue();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("sender failed");
        assertThat(notification.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(notification.getClaimToken()).isNull();
        assertThat(notification.getClaimExpiresAt()).isNull();
    }

    @Test
    void finalizeFailed_shouldBecomeTerminalAtMaxAttempts() {
        Notification notification = pendingNotification(UUID.randomUUID());
        UUID token = claim(notification);

        assertThat(notification.finalizeFailed(token, "terminal failure", 1, Instant.now().plusSeconds(60))).isTrue();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("terminal failure");
        assertThat(notification.getNextAttemptAt()).isNull();
        assertThat(notification.getClaimToken()).isNull();
        assertThat(notification.getClaimExpiresAt()).isNull();
    }

    @Test
    void finalizeFailed_shouldIgnoreWrongTokenWithoutOverwritingClaim() {
        Notification notification = pendingNotification(UUID.randomUUID());
        UUID token = claim(notification);

        assertThat(notification.finalizeFailed(UUID.randomUUID(), "stale failure", 3, Instant.now())).isFalse();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getClaimToken()).isEqualTo(token);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getAttempts()).isEqualTo(1);
    }

    private UUID claim(Notification notification) {
        UUID token = UUID.randomUUID();
        Instant now = Instant.now();
        notification.claim(token, now, now.plusSeconds(60));
        return token;
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
