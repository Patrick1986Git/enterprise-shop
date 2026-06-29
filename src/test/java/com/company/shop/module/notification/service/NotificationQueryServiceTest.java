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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.NotificationDeliveryState;
import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationAttemptsRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationCreatedDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationLastAttemptDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationLastRequeuedDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.exception.NotificationSentDateRangeInvalidException;
import com.company.shop.module.notification.mapper.NotificationMapper;
import com.company.shop.module.notification.repository.NotificationRepository;
import com.company.shop.module.notification.repository.NotificationSpecifications;

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
        when(notificationRepository.countByRequeueCountGreaterThan(0)).thenReturn(4L);
        when(notificationRepository.sumRequeueCount()).thenReturn(6L);

        NotificationSummaryDTO result = service.getSummary();

        assertThat(result.pendingCount()).isEqualTo(3L);
        assertThat(result.sentCount()).isEqualTo(5L);
        assertThat(result.failedCount()).isEqualTo(7L);
        assertThat(result.duePendingCount()).isEqualTo(2L);
        assertThat(result.scheduledPendingCount()).isEqualTo(1L);
        assertThat(result.requeuedNotificationCount()).isEqualTo(4L);
        assertThat(result.totalRequeueCount()).isEqualTo(6L);
        verify(notificationRepository).countByStatus(NotificationStatus.PENDING);
        verify(notificationRepository).countByStatus(NotificationStatus.SENT);
        verify(notificationRepository).countByStatus(NotificationStatus.FAILED);
        ArgumentCaptor<Instant> dueNowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> scheduledNowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(notificationRepository).countDuePending(dueNowCaptor.capture());
        verify(notificationRepository).countScheduledPending(scheduledNowCaptor.capture());
        verify(notificationRepository).countByRequeueCountGreaterThan(0);
        verify(notificationRepository).sumRequeueCount();
        assertThat(dueNowCaptor.getValue()).isNotNull();
        assertThat(scheduledNowCaptor.getValue()).isNotNull();
        assertThat(scheduledNowCaptor.getValue()).isEqualTo(dueNowCaptor.getValue());
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
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(notification), pageable, 1));
        when(notificationMapper.toDto(notification)).thenReturn(response);

        NotificationAdminSearchCriteria normalizedCriteria = NotificationAdminSearchCriteria.builder()
                .status(NotificationStatus.PENDING)
                .sourceEventId(sourceEventId)
                .type("ORDER_PLACED_EMAIL")
                .recipient("CUSTOMER")
                .lastErrorContains("Timeout")
                .requeuedOnly(Boolean.TRUE)
                .attemptsMin(2)
                .attemptsMax(5)
                .lastAttemptFrom(Instant.parse("2026-06-21T00:00:00Z"))
                .lastAttemptTo(Instant.parse("2026-06-21T23:59:59Z"))
                .lastRequeuedFrom(Instant.parse("2026-06-21T01:00:00Z"))
                .lastRequeuedTo(Instant.parse("2026-06-21T22:00:00Z"))
                .createdFrom(Instant.parse("2026-06-20T00:00:00Z"))
                .createdTo(Instant.parse("2026-06-22T00:00:00Z"))
                .build();
        NotificationAdminSearchCriteria inputCriteria = NotificationAdminSearchCriteria.builder()
                .status(NotificationStatus.PENDING)
                .sourceEventId(sourceEventId)
                .type(" ORDER_PLACED_EMAIL ")
                .recipient(" CUSTOMER ")
                .lastErrorContains(" Timeout ")
                .requeuedOnly(Boolean.TRUE)
                .attemptsMin(2)
                .attemptsMax(5)
                .lastAttemptFrom(Instant.parse("2026-06-21T00:00:00Z"))
                .lastAttemptTo(Instant.parse("2026-06-21T23:59:59Z"))
                .lastRequeuedFrom(Instant.parse("2026-06-21T01:00:00Z"))
                .lastRequeuedTo(Instant.parse("2026-06-21T22:00:00Z"))
                .createdFrom(Instant.parse("2026-06-20T00:00:00Z"))
                .createdTo(Instant.parse("2026-06-22T00:00:00Z"))
                .build();

        Page<NotificationResponseDTO> result;
        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(normalizedCriteria))
                    .thenReturn(specification);

            result = service.getNotifications(inputCriteria, pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(normalizedCriteria));
        }

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getNumber()).isEqualTo(1);
        verify(notificationRepository).findAll(specification, pageable);
        verify(notificationMapper).toDto(notification);
    }

    @Test
    void getNotifications_shouldIgnoreBlankStringFilters() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        Pageable pageable = PageRequest.of(0, 10);
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<NotificationResponseDTO> result;
        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(
                    criteria(null, null, null, null, null, null, null)))
                    .thenReturn(specification);

            result = service.getNotifications(
                    criteria(null, " ", " ", " ", null, null, null), pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(
                    criteria(null, null, null, null, null, null, null)));
        }

        assertThat(result.getContent()).isEmpty();
        verify(notificationRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(notificationMapper);
    }



    @Test
    void getNotifications_shouldPassDeliveryStateCriteriaToSpecificationsWithNow() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        Pageable pageable = PageRequest.of(0, 10);
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(
                    org.mockito.Mockito.eq(NotificationAdminSearchCriteria.builder()
                            .deliveryState(NotificationDeliveryState.DUE_PENDING)
                            .type("ORDER_PLACED_EMAIL")
                            .recipient("customer")
                            .build()),
                    org.mockito.Mockito.any(Instant.class)))
                    .thenReturn(specification);

            service.getNotifications(
                    NotificationAdminSearchCriteria.builder()
                            .deliveryState(NotificationDeliveryState.DUE_PENDING)
                            .type(" ORDER_PLACED_EMAIL ")
                            .recipient(" customer ")
                            .build(),
                    pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(
                    org.mockito.Mockito.eq(NotificationAdminSearchCriteria.builder()
                            .deliveryState(NotificationDeliveryState.DUE_PENDING)
                            .type("ORDER_PLACED_EMAIL")
                            .recipient("customer")
                            .build()),
                    org.mockito.Mockito.any(Instant.class)));
        }

        verify(notificationRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(notificationMapper);
    }

    @Test
    void getNotifications_shouldRejectLastAttemptFromAfterLastAttemptTo() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteria(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-06-22T00:00:00Z"),
                        Instant.parse("2026-06-21T00:00:00Z")),
                Pageable.unpaged()))
                .isInstanceOf(NotificationLastAttemptDateRangeInvalidException.class);
    }

    @Test
    void getNotifications_shouldPassValidLastAttemptFiltersToSpecifications() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        Pageable pageable = PageRequest.of(0, 10);
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Instant lastAttemptFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-06-21T23:59:59Z");
        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(
                    criteria(null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo)))
                    .thenReturn(specification);

            service.getNotifications(
                    criteria(null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo), pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(
                    criteria(null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo)));
        }

        verify(notificationRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(notificationMapper);
    }


    @Test
    void getNotifications_shouldRejectLastRequeuedFromAfterLastRequeuedTo() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteriaWithLastRequeuedRange(
                        Instant.parse("2026-06-22T00:00:00Z"),
                        Instant.parse("2026-06-21T00:00:00Z")),
                Pageable.unpaged()))
                .isInstanceOf(NotificationLastRequeuedDateRangeInvalidException.class);
    }

    @Test
    void getNotifications_shouldPassValidLastRequeuedFiltersToSpecifications() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        Pageable pageable = PageRequest.of(0, 10);
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Instant lastRequeuedFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant lastRequeuedTo = Instant.parse("2026-06-21T23:59:59Z");
        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(
                    criteriaWithLastRequeuedRange(lastRequeuedFrom, lastRequeuedTo)))
                    .thenReturn(specification);

            service.getNotifications(criteriaWithLastRequeuedRange(lastRequeuedFrom, lastRequeuedTo), pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(
                    criteriaWithLastRequeuedRange(lastRequeuedFrom, lastRequeuedTo)));
        }

        verify(notificationRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(notificationMapper);
    }

    @Test
    void getNotifications_shouldRejectCreatedFromAfterCreatedTo() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteriaWithCreatedRange(
                        Instant.parse("2026-06-22T00:00:00Z"),
                        Instant.parse("2026-06-21T00:00:00Z")),
                Pageable.unpaged()))
                .isInstanceOf(NotificationCreatedDateRangeInvalidException.class);
    }

    @Test
    void getNotifications_shouldPassValidCreatedFiltersToSpecifications() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        Pageable pageable = PageRequest.of(0, 10);
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Instant createdFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant createdTo = Instant.parse("2026-06-21T23:59:59Z");
        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(
                    criteriaWithCreatedRange(createdFrom, createdTo)))
                    .thenReturn(specification);

            service.getNotifications(criteriaWithCreatedRange(createdFrom, createdTo), pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(
                    criteriaWithCreatedRange(createdFrom, createdTo)));
        }

        verify(notificationRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(notificationMapper);
    }


    @Test
    void getNotifications_shouldRejectSentFromAfterSentTo() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteriaWithSentRange(
                        Instant.parse("2026-06-22T00:00:00Z"),
                        Instant.parse("2026-06-21T00:00:00Z")),
                Pageable.unpaged()))
                .isInstanceOf(NotificationSentDateRangeInvalidException.class);
    }

    @Test
    void getNotifications_shouldPassValidSentFiltersToSpecifications() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);
        Pageable pageable = PageRequest.of(0, 10);
        Specification<Notification> specification = (root, query, cb) -> null;
        when(notificationRepository.findAll(specification, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Instant sentFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant sentTo = Instant.parse("2026-06-21T23:59:59Z");
        try (MockedStatic<NotificationSpecifications> notificationSpecifications =
                org.mockito.Mockito.mockStatic(NotificationSpecifications.class)) {
            notificationSpecifications.when(() -> NotificationSpecifications.adminFilters(
                    criteriaWithSentRange(sentFrom, sentTo)))
                    .thenReturn(specification);

            service.getNotifications(criteriaWithSentRange(sentFrom, sentTo), pageable);

            notificationSpecifications.verify(() -> NotificationSpecifications.adminFilters(
                    criteriaWithSentRange(sentFrom, sentTo)));
        }

        verify(notificationRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(notificationMapper);
    }

    @Test
    void getNotifications_shouldRejectNegativeAttemptsMin() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteria(null, null, null, null, null, -1, null), Pageable.unpaged()))
                .isInstanceOf(NotificationAttemptsRangeInvalidException.class);
    }

    @Test
    void getNotifications_shouldRejectNegativeAttemptsMax() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteria(null, null, null, null, null, null, -1), Pageable.unpaged()))
                .isInstanceOf(NotificationAttemptsRangeInvalidException.class);
    }

    @Test
    void getNotifications_shouldRejectAttemptsMinGreaterThanAttemptsMax() {
        NotificationQueryService service = new NotificationQueryService(notificationRepository, notificationMapper);

        assertThatThrownBy(() -> service.getNotifications(
                criteria(null, null, null, null, null, 5, 2), Pageable.unpaged()))
                .isInstanceOf(NotificationAttemptsRangeInvalidException.class);
    }

    private NotificationAdminSearchCriteria criteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax) {
        return criteria(status, type, recipient, lastErrorContains, requeuedOnly, attemptsMin, attemptsMax, null, null);
    }

    private NotificationAdminSearchCriteria criteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax,
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        return NotificationAdminSearchCriteria.builder()
                .status(status)
                .type(type)
                .recipient(recipient)
                .lastErrorContains(lastErrorContains)
                .requeuedOnly(requeuedOnly)
                .attemptsMin(attemptsMin)
                .attemptsMax(attemptsMax)
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .build();
    }


    private NotificationAdminSearchCriteria criteriaWithLastRequeuedRange(Instant lastRequeuedFrom, Instant lastRequeuedTo) {
        return NotificationAdminSearchCriteria.builder()
                .lastRequeuedFrom(lastRequeuedFrom)
                .lastRequeuedTo(lastRequeuedTo)
                .build();
    }

    private NotificationAdminSearchCriteria criteriaWithCreatedRange(Instant createdFrom, Instant createdTo) {
        return NotificationAdminSearchCriteria.builder()
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .build();
    }

    private NotificationAdminSearchCriteria criteriaWithSentRange(Instant sentFrom, Instant sentTo) {
        return NotificationAdminSearchCriteria.builder()
                .sentFrom(sentFrom)
                .sentTo(sentTo)
                .build();
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
                null,
                null);
    }
}
