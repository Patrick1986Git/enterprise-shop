package com.company.shop.module.notification.delivery;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryPollerTest {

    @Mock
    private NotificationDeliveryProcessor notificationDeliveryProcessor;

    private NotificationDeliveryProperties properties;
    private NotificationDeliveryPoller poller;

    @BeforeEach
    void setUp() {
        properties = new NotificationDeliveryProperties();
        poller = new NotificationDeliveryPoller(notificationDeliveryProcessor, properties);
    }

    @Test
    void processPendingNotifications_shouldNotProcessWhenDisabled() {
        properties.setEnabled(false);

        poller.processPendingNotifications();

        verifyNoInteractions(notificationDeliveryProcessor);
    }

    @Test
    void processPendingNotifications_shouldProcessWhenEnabled() {
        properties.setEnabled(true);
        when(notificationDeliveryProcessor.processPendingBatch(properties.batchSize()))
                .thenReturn(new NotificationDeliveryResult(1, 0));

        poller.processPendingNotifications();

        verify(notificationDeliveryProcessor).processPendingBatch(properties.batchSize());
    }

    @Test
    void processPendingNotifications_shouldPassConfiguredBatchSizeWhenEnabled() {
        properties.setEnabled(true);
        properties.setBatchSize(7);
        when(notificationDeliveryProcessor.processPendingBatch(7)).thenReturn(new NotificationDeliveryResult(1, 0));

        poller.processPendingNotifications();

        verify(notificationDeliveryProcessor).processPendingBatch(7);
    }

    @Test
    void processPendingNotifications_shouldHandleEmptyResultWithoutError() {
        properties.setEnabled(true);
        when(notificationDeliveryProcessor.processPendingBatch(properties.batchSize()))
                .thenReturn(new NotificationDeliveryResult(0, 0));

        poller.processPendingNotifications();

        verify(notificationDeliveryProcessor).processPendingBatch(properties.batchSize());
    }
}
