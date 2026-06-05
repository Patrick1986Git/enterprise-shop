package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
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
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryProcessorTest {

    private static final int BATCH_SIZE = 25;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationSender notificationSender;

    private NotificationDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationDeliveryProcessor(notificationRepository, notificationSender);
    }

    @Test
    void processPendingBatch_shouldSendPendingNotificationAndMarkItSent() {
        Notification notification = pendingNotification();
        when(notificationRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(notification));

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(notificationSender).send(notification);
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    void processPendingBatch_shouldMarkNotificationFailedWhenSenderThrows() {
        Notification notification = pendingNotification();
        when(notificationRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(notification));
        doThrow(new IllegalStateException("sender failed")).when(notificationSender).send(notification);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getLastError()).isEqualTo("sender failed");
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    void processPendingBatch_shouldReturnEmptyResultForEmptyBatch() {
        when(notificationRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of());

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldPassBatchSizeToRepository() {
        int batchSize = 7;
        when(notificationRepository.findPendingBatchForUpdate(batchSize)).thenReturn(List.of());

        processor.processPendingBatch(batchSize);

        verify(notificationRepository).findPendingBatchForUpdate(batchSize);
    }

    @Test
    void processPendingBatch_shouldUseExceptionClassNameWhenExceptionMessageIsNull() {
        Notification notification = pendingNotification();
        when(notificationRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(notification));
        doThrow(new NullMessageException()).when(notificationSender).send(notification);

        NotificationDeliveryResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo(NullMessageException.class.getName());
    }

    @Test
    void processPendingBatch_shouldUseExceptionClassNameWhenExceptionMessageIsBlank() {
        Notification notification = pendingNotification();
        when(notificationRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(notification));
        doThrow(new IllegalStateException("   ")).when(notificationSender).send(notification);

        processor.processPendingBatch(BATCH_SIZE);

        assertThat(notification.getLastError()).isEqualTo(IllegalStateException.class.getName());
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
