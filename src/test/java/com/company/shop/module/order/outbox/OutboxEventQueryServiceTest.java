package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventAttemptsRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventLastAttemptDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;

@ExtendWith(MockitoExtension.class)
class OutboxEventQueryServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    private OutboxEventQueryService outboxEventQueryService;

    @BeforeEach
    void setUp() {
        outboxEventQueryService = new OutboxEventQueryService(outboxEventRepository, outboxEventMapper);
    }

    @Test
    void getSummary_shouldReturnCountsAndTimestampsFromRepository() {
        Instant oldestPendingCreatedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant newestFailedCreatedAt = Instant.parse("2026-01-01T11:00:00Z");
        Instant newestAttemptAt = Instant.parse("2026-01-01T12:00:00Z");
        Instant newestProcessedAttemptAt = Instant.parse("2026-01-01T11:30:00Z");
        Instant newestFailedAttemptAt = Instant.parse("2026-01-01T12:00:00Z");

        when(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(2L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).thenReturn(3L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(1L);
        when(outboxEventRepository.count()).thenReturn(6L);
        when(outboxEventRepository.countByRequeueCountGreaterThan(0)).thenReturn(2L);
        when(outboxEventRepository.sumRequeueCount()).thenReturn(5L);
        when(outboxEventRepository.countByStatusAndCreatedAtLessThanEqual(eq(OutboxEventStatus.PENDING), any(Instant.class)))
                .thenReturn(4L);
        when(outboxEventRepository.countByStatusAndLastAttemptAtLessThanEqual(eq(OutboxEventStatus.FAILED), any(Instant.class)))
                .thenReturn(5L);
        when(outboxEventRepository.countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3))
                .thenReturn(6L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING))
                .thenReturn(Optional.of(oldestPendingCreatedAt));
        when(outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED))
                .thenReturn(Optional.of(newestFailedCreatedAt));
        when(outboxEventRepository.findNewestAttemptAt()).thenReturn(Optional.of(newestAttemptAt));
        when(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED))
                .thenReturn(Optional.of(newestProcessedAttemptAt));
        when(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.FAILED))
                .thenReturn(Optional.of(newestFailedAttemptAt));

        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();

        assertThat(summary.pendingCount()).isEqualTo(2L);
        assertThat(summary.processedCount()).isEqualTo(3L);
        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.totalCount()).isEqualTo(6L);
        assertThat(summary.requeuedEventCount()).isEqualTo(2L);
        assertThat(summary.totalRequeueCount()).isEqualTo(5L);
        assertThat(summary.stalePendingCount()).isEqualTo(4L);
        assertThat(summary.staleFailedCount()).isEqualTo(5L);
        assertThat(summary.highAttemptFailedCount()).isEqualTo(6L);
        assertThat(summary.oldestPendingCreatedAt()).isEqualTo(oldestPendingCreatedAt);
        assertThat(summary.newestFailedCreatedAt()).isEqualTo(newestFailedCreatedAt);
        assertThat(summary.newestAttemptAt()).isEqualTo(newestAttemptAt);
        assertThat(summary.newestProcessedAttemptAt()).isEqualTo(newestProcessedAttemptAt);
        assertThat(summary.newestFailedAttemptAt()).isEqualTo(newestFailedAttemptAt);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).count();
        verify(outboxEventRepository).countByRequeueCountGreaterThan(0);
        verify(outboxEventRepository).sumRequeueCount();
        verify(outboxEventRepository).countByStatusAndCreatedAtLessThanEqual(eq(OutboxEventStatus.PENDING), any(Instant.class));
        verify(outboxEventRepository).countByStatusAndLastAttemptAtLessThanEqual(eq(OutboxEventStatus.FAILED), any(Instant.class));
        verify(outboxEventRepository).countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3);
        verify(outboxEventRepository).findOldestCreatedAtByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).findNewestCreatedAtByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).findNewestAttemptAt();
        verify(outboxEventRepository).findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).findNewestAttemptAtByStatus(OutboxEventStatus.FAILED);
        verifyNoMoreInteractions(outboxEventRepository, outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getSummary_shouldReturnNullTimestampsWhenRepositoryOptionalsAreEmpty() {
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).thenReturn(4L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
        when(outboxEventRepository.count()).thenReturn(4L);
        when(outboxEventRepository.countByRequeueCountGreaterThan(0)).thenReturn(0L);
        when(outboxEventRepository.sumRequeueCount()).thenReturn(0L);
        when(outboxEventRepository.countByStatusAndCreatedAtLessThanEqual(eq(OutboxEventStatus.PENDING), any(Instant.class)))
                .thenReturn(0L);
        when(outboxEventRepository.countByStatusAndLastAttemptAtLessThanEqual(eq(OutboxEventStatus.FAILED), any(Instant.class)))
                .thenReturn(0L);
        when(outboxEventRepository.countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3))
                .thenReturn(0L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING)).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED)).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestAttemptAt()).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED)).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.FAILED)).thenReturn(Optional.empty());

        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();

        assertThat(summary.pendingCount()).isZero();
        assertThat(summary.processedCount()).isEqualTo(4L);
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.totalCount()).isEqualTo(4L);
        assertThat(summary.requeuedEventCount()).isZero();
        assertThat(summary.totalRequeueCount()).isZero();
        assertThat(summary.stalePendingCount()).isZero();
        assertThat(summary.staleFailedCount()).isZero();
        assertThat(summary.highAttemptFailedCount()).isZero();
        assertThat(summary.oldestPendingCreatedAt()).isNull();
        assertThat(summary.newestFailedCreatedAt()).isNull();
        assertThat(summary.newestAttemptAt()).isNull();
        assertThat(summary.newestProcessedAttemptAt()).isNull();
        assertThat(summary.newestFailedAttemptAt()).isNull();
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).count();
        verify(outboxEventRepository).countByRequeueCountGreaterThan(0);
        verify(outboxEventRepository).sumRequeueCount();
        verify(outboxEventRepository).countByStatusAndCreatedAtLessThanEqual(eq(OutboxEventStatus.PENDING), any(Instant.class));
        verify(outboxEventRepository).countByStatusAndLastAttemptAtLessThanEqual(eq(OutboxEventStatus.FAILED), any(Instant.class));
        verify(outboxEventRepository).countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3);
        verify(outboxEventRepository).findOldestCreatedAtByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).findNewestCreatedAtByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).findNewestAttemptAt();
        verify(outboxEventRepository).findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).findNewestAttemptAtByStatus(OutboxEventStatus.FAILED);
        verifyNoMoreInteractions(outboxEventRepository, outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getSummary_shouldIncludeOperationalProblemIndicators() {
        when(outboxEventRepository.countByStatus(any())).thenReturn(0L);
        when(outboxEventRepository.count()).thenReturn(0L);
        when(outboxEventRepository.countByRequeueCountGreaterThan(0)).thenReturn(0L);
        when(outboxEventRepository.sumRequeueCount()).thenReturn(0L);
        when(outboxEventRepository.countByStatusAndCreatedAtLessThanEqual(eq(OutboxEventStatus.PENDING), any(Instant.class)))
                .thenReturn(7L);
        when(outboxEventRepository.countByStatusAndLastAttemptAtLessThanEqual(eq(OutboxEventStatus.FAILED), any(Instant.class)))
                .thenReturn(8L);
        when(outboxEventRepository.countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3))
                .thenReturn(9L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(any())).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestCreatedAtByStatus(any())).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestAttemptAt()).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestAttemptAtByStatus(any())).thenReturn(Optional.empty());

        Instant beforeCall = Instant.now().minusSeconds(901);
        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();
        Instant afterCall = Instant.now().minusSeconds(899);

        assertThat(summary.stalePendingCount()).isEqualTo(7L);
        assertThat(summary.staleFailedCount()).isEqualTo(8L);
        assertThat(summary.highAttemptFailedCount()).isEqualTo(9L);

        ArgumentCaptor<Instant> stalePendingThresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> staleFailedThresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxEventRepository).countByStatusAndCreatedAtLessThanEqual(
                eq(OutboxEventStatus.PENDING), stalePendingThresholdCaptor.capture());
        verify(outboxEventRepository).countByStatusAndLastAttemptAtLessThanEqual(
                eq(OutboxEventStatus.FAILED), staleFailedThresholdCaptor.capture());
        verify(outboxEventRepository).countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3);
        assertThat(stalePendingThresholdCaptor.getValue()).isBetween(beforeCall, afterCall);
        assertThat(staleFailedThresholdCaptor.getValue()).isBetween(beforeCall, afterCall);
    }

    @Test
    void getEvent_shouldReturnMappedDetailDtoWhenEventExists() throws Exception {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":1}");
        setId(event, eventId);
        OutboxEventDetailResponseDTO detail = detailResponse(eventId);
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxEventMapper.toDetailDto(event)).thenReturn(detail);

        OutboxEventDetailResponseDTO result = outboxEventQueryService.getEvent(eventId);

        assertThat(result).isEqualTo(detail);
        verify(outboxEventRepository).findById(eventId);
        verify(outboxEventMapper).toDetailDto(event);
        verifyNoMoreInteractions(outboxEventRepository, outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvent_shouldThrowWhenEventIsMissing() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> outboxEventQueryService.getEvent(eventId))
                .isInstanceOf(OutboxEventNotFoundException.class)
                .hasMessage("Outbox event not found: " + eventId)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_NOT_FOUND");

        verify(outboxEventRepository).findById(eventId);
        verifyNoMoreInteractions(outboxEventRepository);
        verifyNoInteractions(outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldReturnMappedPageAndApplyDefaultSortWhenUnsorted() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        OutboxEventResponseDTO response = response(UUID.randomUUID());
        Pageable requestedPageable = PageRequest.of(1, 5);
        Pageable expectedPageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, expectedPageable))
                .thenReturn(new PageImpl<>(List.of(event), expectedPageable, 1));
        when(outboxEventMapper.toDto(event)).thenReturn(response);

        Page<OutboxEventResponseDTO> result;
        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, null)))
                    .thenReturn(specification);

            result = outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, null), requestedPageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, null)));
        }

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getSort()).containsExactly(Sort.Order.desc("createdAt"));
        verify(outboxEventRepository).findAll(specification, expectedPageable);
        verify(outboxEventMapper).toDto(event);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPreserveExplicitSortAndPassFiltersToSpecification() {
        UUID aggregateId = UUID.randomUUID();
        Instant createdFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-06-30T23:59:59Z");
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "eventType"));
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, pageable)).thenReturn(Page.empty(pageable));

        Page<OutboxEventResponseDTO> result;
        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, " Order ", aggregateId, " Placed ", " timeout ", createdFrom, createdTo, null, null, null, null, Boolean.TRUE)))
                    .thenReturn(specification);

            result = outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, " Order ", aggregateId, " Placed ", " timeout ", createdFrom, createdTo, null, null, null, null, Boolean.TRUE), pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, " Order ", aggregateId, " Placed ", " timeout ", createdFrom, createdTo, null, null, null, null, Boolean.TRUE)));
        }

        assertThat(result.getSort()).containsExactly(Sort.Order.asc("eventType"));
        verify(outboxEventRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }


    @Test
    void getEvents_shouldForwardFalseRequeuedOnlyToSpecification() {
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, Boolean.FALSE)))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, Boolean.FALSE), pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, Boolean.FALSE)));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPassLastAttemptFiltersToSpecification() {
        Instant lastAttemptFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-06-30T23:59:59Z");
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo, null, null, null)))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo, null, null, null), pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo, null, null, null)));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPassAttemptsFiltersToSpecification() {
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, 5, null)))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, 5, null), pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, 5, null)));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPassLastErrorContainsToSpecification() {
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, " timeout ", null, null, null, null, null, null, null)))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, " timeout ", null, null, null, null, null, null, null), pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, " timeout ", null, null, null, null, null, null, null)));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowNullAndBlankLastErrorContains() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, null), pageable);
        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, "   ", null, null, null, null, null, null, null), pageable);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOneSidedAndEqualAttemptsFilters() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 1, null, null), pageable);
        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, 3, null), pageable);
        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, 2, null), pageable);

        verify(outboxEventRepository, org.mockito.Mockito.times(3)).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenAttemptsRangeIsInvalid() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, -1, null, null), pageable))
                .isInstanceOf(OutboxEventAttemptsRangeInvalidException.class)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_ATTEMPTS_RANGE_INVALID");
        assertThatThrownBy(() -> outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, -1, null), pageable))
                .isInstanceOf(OutboxEventAttemptsRangeInvalidException.class);
        assertThatThrownBy(() -> outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 4, 3, null), pageable))
                .isInstanceOf(OutboxEventAttemptsRangeInvalidException.class);

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyLastAttemptFrom() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, Instant.parse("2026-06-01T00:00:00Z"), null, null, null, null), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyLastAttemptTo() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, Instant.parse("2026-06-30T23:59:59Z"), null, null, null), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowEqualLastAttemptFromAndLastAttemptTo() {
        Instant lastAttemptAt = Instant.parse("2026-06-15T12:00:00Z");
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lastAttemptAt, lastAttemptAt, null, null, null), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenLastAttemptFromIsAfterLastAttemptTo() {
        Instant lastAttemptFrom = Instant.parse("2026-07-01T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-06-01T00:00:00Z");

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lastAttemptFrom, lastAttemptTo, null, null, null), PageRequest.of(0, 20)))
                .isInstanceOf(OutboxEventLastAttemptDateRangeInvalidException.class)
                .hasMessage("lastAttemptFrom must be before or equal to lastAttemptTo.")
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_LAST_ATTEMPT_DATE_RANGE_INVALID");

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenCreatedFromIsAfterCreatedTo() {
        Instant createdFrom = Instant.parse("2026-02-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, createdFrom, createdTo, null, null, null, null, Boolean.TRUE), PageRequest.of(0, 20)))
                .isInstanceOf(OutboxEventDateRangeInvalidException.class)
                .hasMessage("createdFrom must be before or equal to createdTo.")
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_DATE_RANGE_INVALID");

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowEqualCreatedFromAndCreatedTo() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, createdAt, createdAt, null, null, null, null, null), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyCreatedFrom() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null, null, Boolean.FALSE), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyCreatedTo() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null, null), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    private OutboxEventDetailResponseDTO detailResponse(UUID id) {
        return new OutboxEventDetailResponseDTO(
                id,
                "Order",
                UUID.randomUUID(),
                "OrderPlaced",
                "{\"id\":1}",
                OutboxEventStatus.PENDING,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                null,
                0,
                null,
                0,
                null,
                null);
    }

    private void setId(Object entity, UUID id) throws Exception {
        var field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private OutboxEventResponseDTO response(UUID id) {
        return new OutboxEventResponseDTO(
                id,
                "Order",
                UUID.randomUUID(),
                "OrderPlaced",
                OutboxEventStatus.PENDING,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                null,
                0,
                null,
                0,
                null,
                null);
    }
}
