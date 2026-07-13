package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import com.company.shop.module.order.outbox.exception.OutboxEventNextAttemptDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventProcessedDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventVersionInvalidException;

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
        Instant oldestDeadLetterCreatedAt = Instant.parse("2026-01-01T09:30:00Z");
        Instant newestDeadLetterAttemptAt = Instant.parse("2026-01-01T12:30:00Z");

        when(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(2L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).thenReturn(3L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(1L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.DEAD_LETTER)).thenReturn(1L);
        when(outboxEventRepository.count()).thenReturn(7L);
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
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.DEAD_LETTER))
                .thenReturn(Optional.of(oldestDeadLetterCreatedAt));
        when(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.DEAD_LETTER))
                .thenReturn(Optional.of(newestDeadLetterAttemptAt));

        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();

        assertThat(summary.pendingCount()).isEqualTo(2L);
        assertThat(summary.processedCount()).isEqualTo(3L);
        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.deadLetterCount()).isEqualTo(1L);
        assertThat(summary.totalCount()).isEqualTo(7L);
        assertThat(summary.requeuedEventCount()).isEqualTo(2L);
        assertThat(summary.totalRequeueCount()).isEqualTo(5L);
        assertThat(summary.stalePendingCount()).isEqualTo(4L);
        assertThat(summary.staleFailedCount()).isEqualTo(5L);
        assertThat(summary.highAttemptFailedCount()).isEqualTo(6L);
        assertThat(summary.staleThresholdMinutes()).isEqualTo(15L);
        assertThat(summary.highFailedAttemptsThreshold()).isEqualTo(3);
        assertThat(summary.oldestPendingCreatedAt()).isEqualTo(oldestPendingCreatedAt);
        assertThat(summary.newestFailedCreatedAt()).isEqualTo(newestFailedCreatedAt);
        assertThat(summary.newestAttemptAt()).isEqualTo(newestAttemptAt);
        assertThat(summary.newestProcessedAttemptAt()).isEqualTo(newestProcessedAttemptAt);
        assertThat(summary.newestFailedAttemptAt()).isEqualTo(newestFailedAttemptAt);
        assertThat(summary.oldestDeadLetterCreatedAt()).isEqualTo(oldestDeadLetterCreatedAt);
        assertThat(summary.newestDeadLetterAttemptAt()).isEqualTo(newestDeadLetterAttemptAt);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.DEAD_LETTER);
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
        verify(outboxEventRepository).findOldestCreatedAtByStatus(OutboxEventStatus.DEAD_LETTER);
        verify(outboxEventRepository).findNewestAttemptAtByStatus(OutboxEventStatus.DEAD_LETTER);
        verifyNoMoreInteractions(outboxEventRepository, outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getSummary_shouldReturnNullTimestampsWhenRepositoryOptionalsAreEmpty() {
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).thenReturn(4L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.DEAD_LETTER)).thenReturn(0L);
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
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.DEAD_LETTER)).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.DEAD_LETTER)).thenReturn(Optional.empty());

        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();

        assertThat(summary.pendingCount()).isZero();
        assertThat(summary.processedCount()).isEqualTo(4L);
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.deadLetterCount()).isZero();
        assertThat(summary.totalCount()).isEqualTo(4L);
        assertThat(summary.requeuedEventCount()).isZero();
        assertThat(summary.totalRequeueCount()).isZero();
        assertThat(summary.stalePendingCount()).isZero();
        assertThat(summary.staleFailedCount()).isZero();
        assertThat(summary.highAttemptFailedCount()).isZero();
        assertThat(summary.staleThresholdMinutes()).isEqualTo(15L);
        assertThat(summary.highFailedAttemptsThreshold()).isEqualTo(3);
        assertThat(summary.oldestPendingCreatedAt()).isNull();
        assertThat(summary.newestFailedCreatedAt()).isNull();
        assertThat(summary.newestAttemptAt()).isNull();
        assertThat(summary.newestProcessedAttemptAt()).isNull();
        assertThat(summary.newestFailedAttemptAt()).isNull();
        assertThat(summary.oldestDeadLetterCreatedAt()).isNull();
        assertThat(summary.newestDeadLetterAttemptAt()).isNull();
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.DEAD_LETTER);
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
        verify(outboxEventRepository).findOldestCreatedAtByStatus(OutboxEventStatus.DEAD_LETTER);
        verify(outboxEventRepository).findNewestAttemptAtByStatus(OutboxEventStatus.DEAD_LETTER);
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
        assertThat(summary.staleThresholdMinutes()).isEqualTo(15L);
        assertThat(summary.highFailedAttemptsThreshold()).isEqualTo(3);

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
    void builder_shouldPopulateAllCriteriaFields() {
        UUID aggregateId = UUID.randomUUID();
        Instant createdFrom = Instant.parse("2026-01-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-02T00:00:00Z");
        Instant processedFrom = Instant.parse("2026-01-03T00:00:00Z");
        Instant processedTo = Instant.parse("2026-01-04T00:00:00Z");
        Instant lastAttemptFrom = Instant.parse("2026-01-05T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-01-06T00:00:00Z");
        Instant nextAttemptFrom = Instant.parse("2026-01-07T00:00:00Z");
        Instant nextAttemptTo = Instant.parse("2026-01-08T00:00:00Z");

        OutboxEventAdminSearchCriteria criteria = OutboxEventAdminSearchCriteria.builder()
                .status(OutboxEventStatus.FAILED)
                .aggregateType("Order")
                .aggregateId(aggregateId)
                .eventType("OrderPlaced")
                .lastErrorContains("timeout")
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .processedFrom(processedFrom)
                .processedTo(processedTo)
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .nextAttemptFrom(nextAttemptFrom)
                .nextAttemptTo(nextAttemptTo)
                .attemptsMin(2)
                .attemptsMax(4)
                .requeuedOnly(Boolean.TRUE)
                .problemType(OutboxEventProblemType.HIGH_ATTEMPT_FAILED)
                .build();

        assertThat(criteria.status()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(criteria.aggregateType()).isEqualTo("Order");
        assertThat(criteria.aggregateId()).isEqualTo(aggregateId);
        assertThat(criteria.eventType()).isEqualTo("OrderPlaced");
        assertThat(criteria.lastErrorContains()).isEqualTo("timeout");
        assertThat(criteria.createdFrom()).isEqualTo(createdFrom);
        assertThat(criteria.createdTo()).isEqualTo(createdTo);
        assertThat(criteria.processedFrom()).isEqualTo(processedFrom);
        assertThat(criteria.processedTo()).isEqualTo(processedTo);
        assertThat(criteria.lastAttemptFrom()).isEqualTo(lastAttemptFrom);
        assertThat(criteria.lastAttemptTo()).isEqualTo(lastAttemptTo);
        assertThat(criteria.nextAttemptFrom()).isEqualTo(nextAttemptFrom);
        assertThat(criteria.nextAttemptTo()).isEqualTo(nextAttemptTo);
        assertThat(criteria.attemptsMin()).isEqualTo(2);
        assertThat(criteria.attemptsMax()).isEqualTo(4);
        assertThat(criteria.requeuedOnly()).isTrue();
        assertThat(criteria.problemType()).isEqualTo(OutboxEventProblemType.HIGH_ATTEMPT_FAILED);
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

        OutboxEventAdminSearchCriteria criteria = emptyCriteria();

        Page<OutboxEventResponseDTO> result;
        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            result = outboxEventQueryService.getEvents(criteria, requestedPageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
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

        OutboxEventAdminSearchCriteria criteria = criteriaWithFilters(
                OutboxEventStatus.FAILED,
                " Order ",
                aggregateId,
                " Placed ",
                " timeout ",
                createdFrom,
                createdTo,
                Boolean.TRUE);

        Page<OutboxEventResponseDTO> result;
        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            result = outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
        }

        assertThat(result.getSort()).containsExactly(Sort.Order.asc("eventType"));
        verify(outboxEventRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPassProblemTypeCriteriaAndStaleThresholdToSpecification() {
        Pageable pageable = PageRequest.of(0, 20);
        Pageable expectedPageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        OutboxEventAdminSearchCriteria criteria = criteriaWithProblemType(OutboxEventProblemType.STALE_FAILED);
        when(outboxEventRepository.findAll(specification, expectedPageable)).thenReturn(Page.empty(expectedPageable));

        Instant beforeExpectedThreshold = Instant.now().minus(Duration.ofMinutes(15));
        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(
                    eq(criteria), any(Instant.class), eq(3)))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
            specifications.verify(() -> OutboxEventSpecifications.adminFilters(
                    eq(criteria), thresholdCaptor.capture(), eq(3)));
            Instant afterExpectedThreshold = Instant.now().minus(Duration.ofMinutes(15));
            assertThat(thresholdCaptor.getValue()).isBetween(beforeExpectedThreshold, afterExpectedThreshold);
        }

        verify(outboxEventRepository).findAll(specification, expectedPageable);
        verifyNoInteractions(outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldForwardFalseRequeuedOnlyToSpecification() {
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        OutboxEventAdminSearchCriteria criteria = criteriaWithRequeuedOnly(Boolean.FALSE);

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPassProcessedFiltersToSpecification() {
        Instant processedFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant processedTo = Instant.parse("2026-06-21T23:59:59Z");
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        OutboxEventAdminSearchCriteria criteria = criteriaWithProcessedRange(processedFrom, processedTo);
        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
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

        OutboxEventAdminSearchCriteria criteria = criteriaWithLastAttemptRange(lastAttemptFrom, lastAttemptTo);

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }


    @Test
    void getEvents_shouldPassNextAttemptFiltersToSpecification() {
        Instant nextAttemptFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant nextAttemptTo = Instant.parse("2026-06-30T23:59:59Z");
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        OutboxEventAdminSearchCriteria criteria = criteriaWithNextAttemptRange(nextAttemptFrom, nextAttemptTo);

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenNextAttemptFromIsAfterNextAttemptTo() {
        Instant nextAttemptFrom = Instant.parse("2026-07-01T00:00:00Z");
        Instant nextAttemptTo = Instant.parse("2026-06-01T00:00:00Z");

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithNextAttemptRange(nextAttemptFrom, nextAttemptTo), PageRequest.of(0, 20)))
                .isInstanceOf(OutboxEventNextAttemptDateRangeInvalidException.class)
                .hasMessage("nextAttemptFrom must be before or equal to nextAttemptTo.")
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_NEXT_ATTEMPT_DATE_RANGE_INVALID");

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldPassAttemptsFiltersToSpecification() {
        Pageable pageable = PageRequest.of(0, 20);
        Specification<OutboxEvent> specification = (root, query, cb) -> null;
        when(outboxEventRepository.findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(Page.empty(pageable));

        OutboxEventAdminSearchCriteria criteria = criteriaWithAttempts(2, 5);

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
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

        OutboxEventAdminSearchCriteria criteria = criteriaWithLastErrorContains(" timeout ");

        try (MockedStatic<OutboxEventSpecifications> specifications =
                org.mockito.Mockito.mockStatic(OutboxEventSpecifications.class)) {
            specifications.when(() -> OutboxEventSpecifications.adminFilters(criteria))
                    .thenReturn(specification);

            outboxEventQueryService.getEvents(criteria, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(criteria));
        }

        verify(outboxEventRepository).findAll(specification, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAcceptPositiveEventVersion() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithEventVersion(1), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenEventVersionIsZeroOrNegative() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithEventVersion(0), pageable))
                .isInstanceOf(OutboxEventVersionInvalidException.class)
                .hasMessage("eventVersion must be greater than or equal to 1.")
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_VERSION_INVALID");
        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithEventVersion(-1), pageable))
                .isInstanceOf(OutboxEventVersionInvalidException.class);

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowNullAndBlankLastErrorContains() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(emptyCriteria(), pageable);
        outboxEventQueryService.getEvents(criteriaWithLastErrorContains("   "), pageable);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOneSidedAndEqualAttemptsFilters() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithAttempts(1, null), pageable);
        outboxEventQueryService.getEvents(criteriaWithAttempts(null, 3), pageable);
        outboxEventQueryService.getEvents(criteriaWithAttempts(2, 2), pageable);

        verify(outboxEventRepository, org.mockito.Mockito.times(3)).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenAttemptsRangeIsInvalid() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithAttempts(-1, null), pageable))
                .isInstanceOf(OutboxEventAttemptsRangeInvalidException.class)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_ATTEMPTS_RANGE_INVALID");
        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithAttempts(null, -1), pageable))
                .isInstanceOf(OutboxEventAttemptsRangeInvalidException.class);
        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithAttempts(4, 3), pageable))
                .isInstanceOf(OutboxEventAttemptsRangeInvalidException.class);

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyLastAttemptFrom() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithLastAttemptRange(Instant.parse("2026-06-01T00:00:00Z"), null), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyLastAttemptTo() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithLastAttemptRange(null, Instant.parse("2026-06-30T23:59:59Z")), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowEqualLastAttemptFromAndLastAttemptTo() {
        Instant lastAttemptAt = Instant.parse("2026-06-15T12:00:00Z");
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithLastAttemptRange(lastAttemptAt, lastAttemptAt), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenProcessedFromIsAfterProcessedTo() {
        Instant processedFrom = Instant.parse("2026-06-22T00:00:00Z");
        Instant processedTo = Instant.parse("2026-06-21T00:00:00Z");

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithProcessedRange(processedFrom, processedTo), PageRequest.of(0, 20)))
                .isInstanceOf(OutboxEventProcessedDateRangeInvalidException.class)
                .hasMessage("processedFrom must be before or equal to processedTo.")
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_PROCESSED_DATE_RANGE_INVALID");

        verifyNoInteractions(outboxEventRepository, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenLastAttemptFromIsAfterLastAttemptTo() {
        Instant lastAttemptFrom = Instant.parse("2026-07-01T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-06-01T00:00:00Z");

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithLastAttemptRange(lastAttemptFrom, lastAttemptTo), PageRequest.of(0, 20)))
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

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(criteriaWithCreatedRangeAndRequeuedOnly(createdFrom, createdTo, Boolean.TRUE), PageRequest.of(0, 20)))
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

        outboxEventQueryService.getEvents(criteriaWithCreatedRange(createdAt, createdAt), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyCreatedFrom() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithCreatedRangeAndRequeuedOnly(Instant.parse("2026-01-01T00:00:00Z"), null, Boolean.FALSE), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyCreatedTo() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(criteriaWithCreatedRange(null, Instant.parse("2026-01-01T00:00:00Z")), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    private static OutboxEventAdminSearchCriteria emptyCriteria() {
        return OutboxEventAdminSearchCriteria.builder()
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithCreatedRange(Instant createdFrom, Instant createdTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithFilters(
            OutboxEventStatus status,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String lastErrorContains,
            Instant createdFrom,
            Instant createdTo,
            Boolean requeuedOnly) {
        return OutboxEventAdminSearchCriteria.builder()
                .status(status)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .lastErrorContains(lastErrorContains)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .requeuedOnly(requeuedOnly)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithCreatedRangeAndRequeuedOnly(
            Instant createdFrom,
            Instant createdTo,
            Boolean requeuedOnly) {
        return OutboxEventAdminSearchCriteria.builder()
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .requeuedOnly(requeuedOnly)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithProcessedRange(Instant processedFrom, Instant processedTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .processedFrom(processedFrom)
                .processedTo(processedTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithLastAttemptRange(
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithNextAttemptRange(
            Instant nextAttemptFrom,
            Instant nextAttemptTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .nextAttemptFrom(nextAttemptFrom)
                .nextAttemptTo(nextAttemptTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithAttempts(Integer attemptsMin, Integer attemptsMax) {
        return OutboxEventAdminSearchCriteria.builder()
                .attemptsMin(attemptsMin)
                .attemptsMax(attemptsMax)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithRequeuedOnly(Boolean requeuedOnly) {
        return OutboxEventAdminSearchCriteria.builder()
                .requeuedOnly(requeuedOnly)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithLastErrorContains(String lastErrorContains) {
        return OutboxEventAdminSearchCriteria.builder()
                .lastErrorContains(lastErrorContains)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithEventVersion(Integer eventVersion) {
        return OutboxEventAdminSearchCriteria.builder()
                .eventVersion(eventVersion)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithProblemType(OutboxEventProblemType problemType) {
        return OutboxEventAdminSearchCriteria.builder()
                .problemType(problemType)
                .build();
    }

    private OutboxEventDetailResponseDTO detailResponse(UUID id) {
        return new OutboxEventDetailResponseDTO(
                id,
                "Order",
                UUID.randomUUID(),
                "OrderPlaced",
                1,
                "{\"id\":1}",
                OutboxEventStatus.PENDING,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                null,
                null,
                0,
                null,
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
                1,
                OutboxEventStatus.PENDING,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                null,
                null,
                0,
                null,
                0,
                null,
                null);
    }
}
