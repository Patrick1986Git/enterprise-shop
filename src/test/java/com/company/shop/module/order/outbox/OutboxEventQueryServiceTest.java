package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;

@ExtendWith(MockitoExtension.class)
class OutboxEventQueryServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    private OutboxEventQueryService outboxEventQueryService;

    @BeforeEach
    void setUp() {
        outboxEventQueryService = new OutboxEventQueryService(outboxEventRepository);
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
        verifyNoMoreInteractions(outboxEventRepository);
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
        verifyNoMoreInteractions(outboxEventRepository);
        verifyNoInteractions(outboxEventProcessor);
    }
}
