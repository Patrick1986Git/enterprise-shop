package com.company.shop.module.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
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
import org.springframework.data.domain.Sort;

import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.entity.NotificationAdminActionLog;
import com.company.shop.module.notification.entity.NotificationAdminActionType;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.mapper.NotificationAdminActionLogMapper;
import com.company.shop.module.notification.repository.NotificationAdminActionLogRepository;
import com.company.shop.module.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationAdminActionLogQueryServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationAdminActionLogRepository notificationAdminActionLogRepository;

    @Mock
    private NotificationAdminActionLogMapper notificationAdminActionLogMapper;

    @Test
    void getNotificationActionLogs_shouldReturnMappedPagedActionLogsForExistingNotification() {
        NotificationAdminActionLogQueryService service = service();
        UUID notificationId = UUID.randomUUID();
        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(notificationId, "admin@example.com");
        NotificationAdminActionLogResponseDTO response = response(notificationId);
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(notificationRepository.existsById(notificationId)).thenReturn(true);
        when(notificationAdminActionLogRepository.findByNotificationId(notificationId, pageable))
                .thenReturn(new PageImpl<>(List.of(log), pageable, 1));
        when(notificationAdminActionLogMapper.toDto(log)).thenReturn(response);

        Page<NotificationAdminActionLogResponseDTO> result = service.getNotificationActionLogs(notificationId, pageable);

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(notificationRepository).existsById(notificationId);
        verify(notificationAdminActionLogRepository).findByNotificationId(notificationId, pageable);
        verify(notificationAdminActionLogMapper).toDto(log);
    }

    @Test
    void getNotificationActionLogs_shouldThrowWhenNotificationDoesNotExist() {
        NotificationAdminActionLogQueryService service = service();
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.existsById(notificationId)).thenReturn(false);

        assertThatThrownBy(() -> service.getNotificationActionLogs(notificationId, PageRequest.of(0, 20)))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessage("Notification not found: " + notificationId);

        verify(notificationRepository).existsById(notificationId);
        verifyNoInteractions(notificationAdminActionLogRepository, notificationAdminActionLogMapper);
    }

    @Test
    void getNotificationActionLogs_shouldApplyDefaultSortWhenPageableIsUnsorted() {
        NotificationAdminActionLogQueryService service = service();
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.existsById(notificationId)).thenReturn(true);
        when(notificationAdminActionLogRepository.findByNotificationId(eq(notificationId), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getNotificationActionLogs(notificationId, PageRequest.of(2, 5));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationAdminActionLogRepository).findByNotificationId(eq(notificationId), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void getNotificationActionLogs_shouldPreserveExplicitPageableSort() {
        NotificationAdminActionLogQueryService service = service();
        UUID notificationId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.ASC, "actorEmail"));
        when(notificationRepository.existsById(notificationId)).thenReturn(true);
        when(notificationAdminActionLogRepository.findByNotificationId(notificationId, pageable)).thenReturn(Page.empty(pageable));

        service.getNotificationActionLogs(notificationId, pageable);

        verify(notificationAdminActionLogRepository).findByNotificationId(notificationId, pageable);
    }

    private NotificationAdminActionLogQueryService service() {
        return new NotificationAdminActionLogQueryService(
                notificationRepository,
                notificationAdminActionLogRepository,
                notificationAdminActionLogMapper);
    }

    private NotificationAdminActionLogResponseDTO response(UUID notificationId) {
        return new NotificationAdminActionLogResponseDTO(
                UUID.randomUUID(),
                notificationId,
                NotificationAdminActionType.REQUEUE,
                "admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"),
                null);
    }
}
