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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.entity.NotificationAdminActionLog;
import com.company.shop.module.notification.entity.NotificationAdminActionType;
import com.company.shop.module.notification.exception.NotificationActionLogDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.mapper.NotificationAdminActionLogMapper;
import com.company.shop.module.notification.repository.NotificationAdminActionLogRepository;
import com.company.shop.module.notification.repository.NotificationAdminActionLogSpecifications;
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

    @Test
    void searchActionLogs_shouldReturnMappedPage() {
        NotificationAdminActionLogQueryService service = service();
        UUID notificationId = UUID.randomUUID();
        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(notificationId, "admin@example.com");
        NotificationAdminActionLogResponseDTO response = response(notificationId);
        Instant createdFrom = Instant.parse("2026-01-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-31T23:59:59Z");
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<NotificationAdminActionLog> specification = (root, query, cb) -> null;
        when(notificationAdminActionLogRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(log), pageable, 1));
        when(notificationAdminActionLogMapper.toDto(log)).thenReturn(response);

        Page<NotificationAdminActionLogResponseDTO> result;
        try (MockedStatic<NotificationAdminActionLogSpecifications> notificationAdminActionLogSpecifications =
                org.mockito.Mockito.mockStatic(NotificationAdminActionLogSpecifications.class)) {
            notificationAdminActionLogSpecifications.when(() -> NotificationAdminActionLogSpecifications.adminFilters(
                    notificationId,
                    NotificationAdminActionType.REQUEUE,
                    "admin",
                    createdFrom,
                    createdTo))
                    .thenReturn(specification);

            result = service.searchActionLogs(
                    notificationId, NotificationAdminActionType.REQUEUE, "admin", createdFrom, createdTo, pageable);

            notificationAdminActionLogSpecifications.verify(() -> NotificationAdminActionLogSpecifications.adminFilters(
                    notificationId,
                    NotificationAdminActionType.REQUEUE,
                    "admin",
                    createdFrom,
                    createdTo));
        }

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(notificationAdminActionLogRepository).findAll(specification, pageable);
        verify(notificationAdminActionLogMapper).toDto(log);
    }

    @Test
    void searchActionLogs_shouldThrowWhenCreatedFromIsAfterCreatedTo() {
        NotificationAdminActionLogQueryService service = service();
        Instant createdFrom = Instant.parse("2026-02-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.searchActionLogs(
                        null, null, null, createdFrom, createdTo, PageRequest.of(0, 20)))
                .isInstanceOf(NotificationActionLogDateRangeInvalidException.class)
                .hasMessage("createdFrom must be before or equal to createdTo.")
                .extracting("errorCode")
                .isEqualTo("NOTIFICATION_ACTION_LOG_DATE_RANGE_INVALID");

        verifyNoInteractions(notificationAdminActionLogRepository, notificationAdminActionLogMapper);
    }

    @Test
    void searchActionLogs_shouldAllowEqualCreatedFromAndCreatedTo() {
        NotificationAdminActionLogQueryService service = service();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        when(notificationAdminActionLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.searchActionLogs(null, null, null, createdAt, createdAt, PageRequest.of(0, 20));

        verify(notificationAdminActionLogRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void searchActionLogs_shouldAllowOnlyCreatedFrom() {
        NotificationAdminActionLogQueryService service = service();
        when(notificationAdminActionLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.searchActionLogs(
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"), null, PageRequest.of(0, 20));

        verify(notificationAdminActionLogRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void searchActionLogs_shouldAllowOnlyCreatedTo() {
        NotificationAdminActionLogQueryService service = service();
        when(notificationAdminActionLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.searchActionLogs(
                null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"), PageRequest.of(0, 20));

        verify(notificationAdminActionLogRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void searchActionLogs_shouldApplyDefaultSortWhenPageableIsUnsorted() {
        NotificationAdminActionLogQueryService service = service();
        when(notificationAdminActionLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.searchActionLogs(null, null, null, null, null, PageRequest.of(2, 5));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationAdminActionLogRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void searchActionLogs_shouldPreserveExplicitPageableSort() {
        NotificationAdminActionLogQueryService service = service();
        Pageable pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.ASC, "actorEmail"));
        when(notificationAdminActionLogRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        service.searchActionLogs(null, null, null, null, null, pageable);

        verify(notificationAdminActionLogRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void searchActionLogs_shouldNotRequireNotificationExistence() {
        NotificationAdminActionLogQueryService service = service();
        UUID notificationId = UUID.randomUUID();
        when(notificationAdminActionLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<NotificationAdminActionLogResponseDTO> result = service.searchActionLogs(
                notificationId, null, null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(notificationRepository);
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
