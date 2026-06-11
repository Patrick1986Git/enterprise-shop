package com.company.shop.module.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.mapper.NotificationMapper;
import com.company.shop.module.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @Test
    void getNotification_shouldReturnNotificationWhenExists() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
        NotificationResponseDTO response = response(notificationId, sourceEventId);
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(notificationMapper.toDto(notification)).thenReturn(response);

        NotificationResponseDTO result = service.getNotification(notificationId);

        assertThat(result).isSameAs(response);
        verify(notificationRepository).findById(notificationId);
        verify(notificationMapper).toDto(notification);
    }

    @Test
    void getNotification_shouldThrowWhenMissing() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNotification(notificationId))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessage("Notification not found: " + notificationId);

        verify(notificationRepository).findById(notificationId);
        verifyNoMoreInteractions(notificationMapper);
    }

    @Test
    void getSummary_shouldReturnCountsFromRepository() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        when(notificationRepository.countByStatus(NotificationStatus.PENDING)).thenReturn(3L);
        when(notificationRepository.countByStatus(NotificationStatus.SENT)).thenReturn(5L);
        when(notificationRepository.countByStatus(NotificationStatus.FAILED)).thenReturn(7L);
        when(notificationRepository.countDuePending(any(Instant.class))).thenReturn(2L);
        when(notificationRepository.countScheduledPending(any(Instant.class))).thenReturn(1L);

        NotificationSummaryDTO result = service.getSummary();

        assertThat(result.pendingCount()).isEqualTo(3L);
        assertThat(result.sentCount()).isEqualTo(5L);
        assertThat(result.failedCount()).isEqualTo(7L);
        assertThat(result.duePendingCount()).isEqualTo(2L);
        assertThat(result.scheduledPendingCount()).isEqualTo(1L);
        verify(notificationRepository).countByStatus(NotificationStatus.PENDING);
        verify(notificationRepository).countByStatus(NotificationStatus.SENT);
        verify(notificationRepository).countByStatus(NotificationStatus.FAILED);
        ArgumentCaptor<Instant> dueNowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> scheduledNowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(notificationRepository).countDuePending(dueNowCaptor.capture());
        verify(notificationRepository).countScheduledPending(scheduledNowCaptor.capture());
        assertThat(dueNowCaptor.getValue()).isNotNull();
        assertThat(scheduledNowCaptor.getValue()).isNotNull();
    }

    @Test
    void getNotifications_shouldMapPagedResultsAndNormalizeFilters() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
        NotificationResponseDTO response = response(notificationId, sourceEventId);
        Pageable pageable = PageRequest.of(1, 10);
        when(notificationRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notification), pageable, 1));
        when(notificationMapper.toDto(notification)).thenReturn(response);

        Page<NotificationResponseDTO> result = service.getNotifications(
                NotificationStatus.PENDING,
                " ORDER_PLACED_EMAIL ",
                " CUSTOMER ",
                pageable);

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getNumber()).isEqualTo(1);
        ArgumentCaptor<Specification<Notification>> specificationCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findAll(
                specificationCaptor.capture(),
                pageableCaptor.capture());
        assertThat(specificationCaptor.getValue()).isNotNull();
        assertThat(pageableCaptor.getValue()).isSameAs(pageable);
        verify(notificationMapper).toDto(notification);
    }

    private NotificationResponseDTO response(UUID notificationId, UUID sourceEventId) {
        return new NotificationResponseDTO(
                notificationId,
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                NotificationStatus.PENDING,
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
