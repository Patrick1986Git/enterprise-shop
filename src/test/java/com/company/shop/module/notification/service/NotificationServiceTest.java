package com.company.shop.module.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Test
    void createOrderPlacedNotification_shouldCreatePendingEmailNotification() {
        NotificationService service = new NotificationService(notificationRepository);
        UUID orderId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        when(notificationRepository.findBySourceEventId(sourceEventId)).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Notification notification = service.createOrderPlacedNotification(
                orderId,
                "customer@example.com",
                new BigDecimal("42.50"),
                sourceEventId);

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notification).isSameAs(notificationCaptor.getValue());
        assertThat(notification.getType()).isEqualTo("ORDER_PLACED_EMAIL");
        assertThat(notification.getRecipient()).isEqualTo("customer@example.com");
        assertThat(notification.getSubject()).isEqualTo("Order placed: " + orderId);
        assertThat(notification.getBody())
                .isEqualTo("Your order " + orderId + " has been placed. Total amount: 42.50.");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(notification.getCreatedAt()).isNotNull();
    }

    @Test
    void createOrderPlacedNotification_shouldReturnExistingNotificationWhenSourceEventIdAlreadyExists() {
        NotificationService service = new NotificationService(notificationRepository);
        UUID orderId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        Notification existingNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed: " + orderId,
                "Your order has been placed.",
                sourceEventId);
        when(notificationRepository.findBySourceEventId(sourceEventId)).thenReturn(Optional.of(existingNotification));

        Notification notification = service.createOrderPlacedNotification(
                orderId,
                "customer@example.com",
                new BigDecimal("42.50"),
                sourceEventId);

        assertThat(notification).isSameAs(existingNotification);
        verify(notificationRepository, never()).save(any(Notification.class));
    }
}
