package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryProcessorTest {

    private static final int BATCH_SIZE = 25;

    @Mock
    private NotificationDeliveryTransactionalWorker transactionalWorker;

    @Mock
    private NotificationSender notificationSender;

    private NotificationDeliveryProperties properties;
    private NotificationDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new NotificationDeliveryProperties();
        processor = new NotificationDeliveryProcessor(transactionalWorker, notificationSender);
    }

    @Test
    void processPendingBatch_shouldSendPendingNotificationAndMarkItSent() {
        Notification notification = pendingNotification();
        notification.markDeliveryAttemptFailed(
                "temporary failure",
                properties.maxAttempts(),
                Instant.now().minusSeconds(60));
        stubClaim(BATCH_SIZE, notification);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(notificationSender).send(notification);
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void processPendingBatch_shouldKeepNotificationPendingWhenSenderThrowsAndAttemptsRemainBelowMaxAttempts() {
        Notification notification = pendingNotification();
        stubClaim(BATCH_SIZE, notification);
        doThrow(new IllegalStateException("sender failed")).when(notificationSender).send(notification);
        Instant beforeProcessing = Instant.now();

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("sender failed");
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isAfter(beforeProcessing);
    }

    @Test
    void processPendingBatch_shouldMarkNotificationFailedWhenSenderThrowsAndAttemptsReachMaxAttempts() {
        properties.setMaxAttempts(2);
        Notification notification = pendingNotification();
        notification.markDeliveryAttemptFailed(
                "first temporary failure",
                properties.maxAttempts(),
                Instant.now().plusSeconds(60));
        stubClaim(BATCH_SIZE, notification);
        doThrow(new IllegalStateException("sender failed")).when(notificationSender).send(notification);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(2);
        assertThat(notification.getLastError()).isEqualTo("sender failed");
        assertThat(notification.getLastAttemptAt()).isNotNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void processPendingBatch_shouldReturnEmptyResultForEmptyBatch() {
        when(transactionalWorker.claimBatch(BATCH_SIZE)).thenReturn(List.of());

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldPassBatchSizeToRepository() {
        int batchSize = 7;
        when(transactionalWorker.claimBatch(batchSize)).thenReturn(List.of());

        processor.processPendingBatch(batchSize);

        verify(transactionalWorker).claimBatch(batchSize);
    }

    @Test
    void processPendingBatch_shouldUseExceptionClassNameWhenExceptionMessageIsNull() {
        Notification notification = pendingNotification();
        stubClaim(BATCH_SIZE, notification);
        doThrow(new NullMessageException()).when(notificationSender).send(notification);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo(NullMessageException.class.getName());
    }

    @Test
    void processPendingBatch_shouldUseExceptionClassNameWhenExceptionMessageIsBlank() {
        Notification notification = pendingNotification();
        stubClaim(BATCH_SIZE, notification);
        doThrow(new IllegalStateException("   ")).when(notificationSender).send(notification);

        processor.processPendingBatch(BATCH_SIZE);

        assertThat(notification.getLastError()).isEqualTo(IllegalStateException.class.getName());
    }

    private void stubClaim(int batchSize, Notification notification) {
        UUID token = UUID.randomUUID();
        when(transactionalWorker.claimBatch(batchSize)).thenReturn(List.of(new ClaimedNotification(notification, token)));
        when(transactionalWorker.finalizeSuccess(notification.getId(), token)).thenReturn(true);
        when(transactionalWorker.finalizeFailure(org.mockito.ArgumentMatchers.eq(notification.getId()),
                org.mockito.ArgumentMatchers.eq(token), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    private Notification pendingNotification() {
        return Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
    }

    private static final class NullMessageException extends RuntimeException {
    }
}
