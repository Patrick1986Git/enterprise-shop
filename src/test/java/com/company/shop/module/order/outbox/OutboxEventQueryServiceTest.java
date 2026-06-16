package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventDateRangeInvalidException;

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

        when(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(2L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).thenReturn(3L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(1L);
        when(outboxEventRepository.count()).thenReturn(6L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING))
                .thenReturn(Optional.of(oldestPendingCreatedAt));
        when(outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED))
                .thenReturn(Optional.of(newestFailedCreatedAt));

        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();

        assertThat(summary.pendingCount()).isEqualTo(2L);
        assertThat(summary.processedCount()).isEqualTo(3L);
        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.totalCount()).isEqualTo(6L);
        assertThat(summary.oldestPendingCreatedAt()).isEqualTo(oldestPendingCreatedAt);
        assertThat(summary.newestFailedCreatedAt()).isEqualTo(newestFailedCreatedAt);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).count();
        verify(outboxEventRepository).findOldestCreatedAtByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).findNewestCreatedAtByStatus(OutboxEventStatus.FAILED);
        verifyNoMoreInteractions(outboxEventRepository, outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getSummary_shouldReturnNullTimestampsWhenRepositoryOptionalsAreEmpty() {
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).thenReturn(4L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
        when(outboxEventRepository.count()).thenReturn(4L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING)).thenReturn(Optional.empty());
        when(outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED)).thenReturn(Optional.empty());

        OutboxEventSummaryDTO summary = outboxEventQueryService.getSummary();

        assertThat(summary.pendingCount()).isZero();
        assertThat(summary.processedCount()).isEqualTo(4L);
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.totalCount()).isEqualTo(4L);
        assertThat(summary.oldestPendingCreatedAt()).isNull();
        assertThat(summary.newestFailedCreatedAt()).isNull();
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.PROCESSED);
        verify(outboxEventRepository).countByStatus(OutboxEventStatus.FAILED);
        verify(outboxEventRepository).count();
        verify(outboxEventRepository).findOldestCreatedAtByStatus(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).findNewestCreatedAtByStatus(OutboxEventStatus.FAILED);
        verifyNoMoreInteractions(outboxEventRepository, outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
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
            specifications.when(() -> OutboxEventSpecifications.adminFilters(null, null, null, null, null, null))
                    .thenReturn(specification);

            result = outboxEventQueryService.getEvents(null, null, null, null, null, null, requestedPageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(null, null, null, null, null, null));
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
            specifications.when(() -> OutboxEventSpecifications.adminFilters(
                    OutboxEventStatus.FAILED, " Order ", aggregateId, " Placed ", createdFrom, createdTo))
                    .thenReturn(specification);

            result = outboxEventQueryService.getEvents(
                    OutboxEventStatus.FAILED, " Order ", aggregateId, " Placed ", createdFrom, createdTo, pageable);

            specifications.verify(() -> OutboxEventSpecifications.adminFilters(
                    OutboxEventStatus.FAILED, " Order ", aggregateId, " Placed ", createdFrom, createdTo));
        }

        assertThat(result.getSort()).containsExactly(Sort.Order.asc("eventType"));
        verify(outboxEventRepository).findAll(specification, pageable);
        verifyNoMoreInteractions(outboxEventMapper);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldThrowWhenCreatedFromIsAfterCreatedTo() {
        Instant createdFrom = Instant.parse("2026-02-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> outboxEventQueryService.getEvents(
                        null, null, null, null, createdFrom, createdTo, PageRequest.of(0, 20)))
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

        outboxEventQueryService.getEvents(null, null, null, null, createdAt, createdAt, pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyCreatedFrom() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(
                null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"), null, pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getEvents_shouldAllowOnlyCreatedTo() {
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        outboxEventQueryService.getEvents(
                null, null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"), pageable);

        verify(outboxEventRepository).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(outboxEventProcessor);
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
                0,
                null);
    }
}
