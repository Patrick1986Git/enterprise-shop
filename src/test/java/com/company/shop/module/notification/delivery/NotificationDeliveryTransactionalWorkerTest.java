package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryTransactionalWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final Instant CLAIMED_AT = Instant.parse("2026-09-26T11:59:00Z");

    @Mock
    private NotificationRepository repository;
    @Mock
    private Clock clock;

    private NotificationDeliveryProperties properties;
    private NotificationDeliveryTransactionalWorker worker;

    @BeforeEach
    void setUp() {
        properties = new NotificationDeliveryProperties();
        properties.setClaimDuration(Duration.ofMinutes(2));
        worker = new NotificationDeliveryTransactionalWorker(repository, properties, clock);
    }

    @Test
    void claimBatch_shouldClaimDueNotificationAndCountOneAttempt() {
        Notification notification = pendingNotification();
        when(clock.instant()).thenReturn(NOW);
        when(repository.findClaimableBatchForUpdate(eq(1), any(), eq(properties.maxAttempts())))
                .thenReturn(List.of(notification));

        List<ClaimedNotification> claims = worker.claimBatch(1);

        verify(repository).failExhaustedExpiredClaims(NOW, properties.maxAttempts());
        verify(repository).findClaimableBatchForUpdate(1, NOW, properties.maxAttempts());
        assertThat(claims).hasSize(1);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getClaimToken()).isEqualTo(claims.getFirst().token());
        assertThat(notification.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(notification.getClaimExpiresAt()).isEqualTo(NOW.plus(properties.claimDuration()));
        assertThat(notification.getAttempts()).isEqualTo(1);
    }

    @Test
    void finalizeSuccess_shouldUseTokenGuardedEntityTransition() {
        Notification notification = pendingNotification();
        UUID token = claim(notification);
        when(repository.findByIdForUpdate(notification.getId())).thenReturn(Optional.of(notification));
        when(clock.instant()).thenReturn(NOW);

        assertThat(worker.finalizeSuccess(notification.getId(), token)).isTrue();

        InOrder finalizationOrder = inOrder(repository, clock);
        finalizationOrder.verify(repository).findByIdForUpdate(notification.getId());
        finalizationOrder.verify(clock).instant();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW);
        assertThat(notification.getLastAttemptAt()).isEqualTo(CLAIMED_AT);
        assertThat(notification.getClaimToken()).isNull();
        assertThat(notification.getClaimExpiresAt()).isNull();
    }

    @Test
    void finalizeSuccess_shouldRejectStaleTokenWithoutSamplingClock() {
        Notification notification = pendingNotification();
        UUID ownerToken = claim(notification);
        when(repository.findByIdForUpdate(notification.getId())).thenReturn(Optional.of(notification));

        assertThat(worker.finalizeSuccess(notification.getId(), UUID.randomUUID())).isFalse();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getClaimToken()).isEqualTo(ownerToken);
        assertThat(notification.getClaimExpiresAt()).isEqualTo(CLAIMED_AT.plusSeconds(60));
        assertThat(notification.getLastAttemptAt()).isEqualTo(CLAIMED_AT);
        assertThat(notification.getSentAt()).isNull();
        verifyNoInteractions(clock);
    }

    @Test
    void finalizeFailure_shouldRejectStaleToken() {
        Notification notification = pendingNotification();
        UUID ownerToken = claim(notification);
        when(repository.findByIdForUpdate(notification.getId())).thenReturn(Optional.of(notification));

        assertThat(worker.finalizeFailure(notification.getId(), UUID.randomUUID(), "stale failure")).isFalse();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getClaimToken()).isEqualTo(ownerToken);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getLastAttemptAt()).isEqualTo(CLAIMED_AT);
        verifyNoInteractions(clock);
    }

    @Test
    void finalizeFailure_shouldScheduleRetryFromFailureCompletionTime() {
        Notification notification = pendingNotification();
        UUID token = claim(notification);
        when(repository.findByIdForUpdate(notification.getId())).thenReturn(Optional.of(notification));
        when(clock.instant()).thenReturn(NOW);

        assertThat(worker.finalizeFailure(notification.getId(), token, "provider unavailable")).isTrue();

        InOrder finalizationOrder = inOrder(repository, clock);
        finalizationOrder.verify(repository).findByIdForUpdate(notification.getId());
        finalizationOrder.verify(clock).instant();
        assertThat(notification.getLastAttemptAt()).isEqualTo(CLAIMED_AT);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW.plus(properties.retryDelay()));
        assertThat(notification.getClaimToken()).isNull();
        assertThat(notification.getClaimExpiresAt()).isNull();
    }

    private UUID claim(Notification notification) {
        UUID token = UUID.randomUUID();
        notification.claim(token, CLAIMED_AT, CLAIMED_AT.plusSeconds(60));
        return token;
    }

    private Notification pendingNotification() {
        return Notification.pending("ORDER_PLACED_EMAIL", "customer@example.com", "Order placed",
                "Your order has been placed.", UUID.randomUUID());
    }
}
