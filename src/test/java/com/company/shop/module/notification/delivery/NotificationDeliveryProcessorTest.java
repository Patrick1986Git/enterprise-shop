package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.notification.entity.Notification;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryProcessorTest {

    private static final int BATCH_SIZE = 25;

    @Mock
    private NotificationDeliveryTransactionalWorker transactionalWorker;
    @Mock
    private NotificationSender notificationSender;

    private NotificationDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationDeliveryProcessor(transactionalWorker, notificationSender);
    }

    @Test
    void processPendingBatch_shouldSendAndFinalizeSuccessfulClaim() {
        Notification notification = pendingNotification();
        UUID token = stubClaim(notification);
        when(transactionalWorker.finalizeSuccess(notification.getId(), token)).thenReturn(true);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(transactionalWorker, times(2)).claimBatch(1);
        verify(notificationSender).send(notification);
        verify(transactionalWorker).finalizeSuccess(notification.getId(), token);
        verify(transactionalWorker, never()).finalizeFailure(notification.getId(), token, "sender failed");
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldNotCountSuccessWhenClaimCanNoLongerBeFinalized() {
        Notification notification = pendingNotification();
        UUID token = stubClaim(notification);
        when(transactionalWorker.finalizeSuccess(notification.getId(), token)).thenReturn(false);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldFinalizeSenderFailure() {
        Notification notification = pendingNotification();
        UUID token = stubClaim(notification);
        doThrow(new IllegalStateException("sender failed")).when(notificationSender).send(notification);
        when(transactionalWorker.finalizeFailure(notification.getId(), token, "sender failed")).thenReturn(true);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(transactionalWorker).finalizeFailure(notification.getId(), token, "sender failed");
        verify(transactionalWorker, never()).finalizeSuccess(notification.getId(), token);
        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void processPendingBatch_shouldNotCountFailureWhenClaimCanNoLongerBeFinalized() {
        Notification notification = pendingNotification();
        UUID token = stubClaim(notification);
        doThrow(new IllegalStateException("sender failed")).when(notificationSender).send(notification);
        when(transactionalWorker.finalizeFailure(notification.getId(), token, "sender failed")).thenReturn(false);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldReturnEmptyResultForEmptyBatch() {
        when(transactionalWorker.claimBatch(1)).thenReturn(List.of());

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(transactionalWorker).claimBatch(1);
        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldUseExceptionClassNameWhenExceptionMessageIsNull() {
        Notification notification = pendingNotification();
        UUID token = stubClaim(notification);
        doThrow(new NullMessageException()).when(notificationSender).send(notification);
        when(transactionalWorker.finalizeFailure(notification.getId(), token, NullMessageException.class.getName()))
                .thenReturn(true);

        processor.processPendingBatch(BATCH_SIZE);

        verify(transactionalWorker).finalizeFailure(notification.getId(), token, NullMessageException.class.getName());
    }

    @Test
    void processPendingBatch_shouldUseExceptionClassNameWhenExceptionMessageIsBlank() {
        Notification notification = pendingNotification();
        UUID token = stubClaim(notification);
        doThrow(new IllegalStateException("   ")).when(notificationSender).send(notification);
        when(transactionalWorker.finalizeFailure(notification.getId(), token, IllegalStateException.class.getName()))
                .thenReturn(true);

        processor.processPendingBatch(BATCH_SIZE);

        verify(transactionalWorker).finalizeFailure(notification.getId(), token, IllegalStateException.class.getName());
    }

    private UUID stubClaim(Notification notification) {
        UUID token = UUID.randomUUID();
        when(transactionalWorker.claimBatch(1))
                .thenReturn(List.of(new ClaimedNotification(notification, token)), List.of());
        return token;
    }

    private Notification pendingNotification() {
        return Notification.pending("ORDER_PLACED_EMAIL", "customer@example.com", "Order placed",
                "Your order has been placed.", UUID.randomUUID());
    }

    private static final class NullMessageException extends RuntimeException {
    }
}
