package com.company.shop.module.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.notification.delivery.NotificationDeliveryProcessor;
import com.company.shop.module.notification.delivery.NotificationSender;
import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.exception.NotificationRequeueNotAllowedException;
import com.company.shop.module.notification.mapper.NotificationMapper;
import com.company.shop.module.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationAdminCommandServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @Test
    void requeueFailedNotification_shouldRequeueFailedNotificationAndReturnMappedDto() {
        NotificationAdminCommandService service = new NotificationAdminCommandService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        Notification notification = failedNotification(sourceEventId);
        NotificationResponseDTO response = response(notificationId, sourceEventId, NotificationStatus.PENDING);
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(notificationMapper.toDto(notification)).thenReturn(response);

        NotificationResponseDTO result = service.requeueFailedNotification(notificationId);

        assertThat(result).isEqualTo(response);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getRequeueCount()).isEqualTo(1);
        assertThat(notification.getLastRequeuedAt()).isNotNull();
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
        verify(notificationRepository).findById(notificationId);
        verify(notificationMapper).toDto(notification);
        verifyNoMoreInteractions(notificationRepository, notificationMapper);
    }

    @Test
    void requeueFailedNotification_shouldThrowWhenNotificationMissing() {
        NotificationAdminCommandService service = new NotificationAdminCommandService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requeueFailedNotification(notificationId))
                .isInstanceOf(NotificationNotFoundException.class)
                .extracting("errorCode")
                .isEqualTo("NOTIFICATION_NOT_FOUND");

        verify(notificationRepository).findById(notificationId);
        verifyNoInteractions(notificationMapper);
    }

    @Test
    void requeueFailedNotification_shouldThrowWhenNotificationPending() {
        NotificationAdminCommandService service = new NotificationAdminCommandService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        Notification notification = pendingNotification(UUID.randomUUID());
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.requeueFailedNotification(notificationId))
                .isInstanceOf(NotificationRequeueNotAllowedException.class)
                .extracting("errorCode")
                .isEqualTo("NOTIFICATION_REQUEUE_NOT_ALLOWED");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        verify(notificationRepository).findById(notificationId);
        verifyNoInteractions(notificationMapper);
    }

    @Test
    void requeueFailedNotification_shouldThrowWhenNotificationSent() {
        NotificationAdminCommandService service = new NotificationAdminCommandService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        Notification notification = pendingNotification(UUID.randomUUID());
        notification.markSent();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.requeueFailedNotification(notificationId))
                .isInstanceOf(NotificationRequeueNotAllowedException.class)
                .extracting("errorCode")
                .isEqualTo("NOTIFICATION_REQUEUE_NOT_ALLOWED");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(notificationRepository).findById(notificationId);
        verifyNoInteractions(notificationMapper);
    }

    @Test
    void requeueFailedNotification_shouldNotDependOnSenderOrDeliveryProcessor() {
        assertThat(Arrays.stream(NotificationAdminCommandService.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .toList())
                .doesNotContain(
                        NotificationSender.class.getName(),
                        NotificationDeliveryProcessor.class.getName());
    }

    private Notification failedNotification(UUID sourceEventId) {
        Notification notification = pendingNotification(sourceEventId);
        notification.markDeliveryAttemptFailed("first temporary failure", 2, Instant.now().plusSeconds(60));
        notification.markDeliveryAttemptFailed("delivery failed", 2, Instant.now().plusSeconds(60));
        return notification;
    }

    private Notification pendingNotification(UUID sourceEventId) {
        return Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
    }

    private NotificationResponseDTO response(UUID notificationId, UUID sourceEventId, NotificationStatus status) {
        return new NotificationResponseDTO(
                notificationId,
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                status,
                sourceEventId,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                0,
                0,
                null,
                null,
                null,
                null);
    }
}
