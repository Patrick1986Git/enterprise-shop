package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    private static final int BATCH_SIZE = 25;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventTransactionalWorker transactionalWorker;

    @Mock
    private OutboxEventFailureRecorder failureRecorder;

    private OutboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessor(outboxEventRepository, transactionalWorker, failureRecorder);
    }

    @Test
    void processPendingBatch_shouldAggregateProcessedFailedAndSkippedOutcomes() {
        UUID processedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID skippedId = UUID.randomUUID();
        when(outboxEventRepository.findDuePendingCandidateIds(BATCH_SIZE))
                .thenReturn(List.of(processedId, failedId, skippedId));
        when(transactionalWorker.processEvent(processedId)).thenReturn(OutboxEventProcessingOutcome.PROCESSED);
        RuntimeException failure = new IllegalStateException("handler failed");
        when(transactionalWorker.processEvent(failedId)).thenThrow(failure);
        when(failureRecorder.recordRetryableFailure(failedId, failure)).thenReturn(OutboxEventProcessingOutcome.FAILED);
        when(transactionalWorker.processEvent(skippedId)).thenReturn(OutboxEventProcessingOutcome.SKIPPED);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void processPendingBatch_shouldContinueAfterFailedCandidate() {
        UUID failedId = UUID.randomUUID();
        UUID processedId = UUID.randomUUID();
        RuntimeException failure = new IllegalStateException("handler failed");
        when(outboxEventRepository.findDuePendingCandidateIds(BATCH_SIZE)).thenReturn(List.of(failedId, processedId));
        when(transactionalWorker.processEvent(failedId)).thenThrow(failure);
        when(failureRecorder.recordRetryableFailure(failedId, failure)).thenReturn(OutboxEventProcessingOutcome.FAILED);
        when(transactionalWorker.processEvent(processedId)).thenReturn(OutboxEventProcessingOutcome.PROCESSED);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(transactionalWorker).processEvent(processedId);
    }

    @Test
    void processPendingBatch_shouldRecordNonRetryableFailure() {
        UUID failedId = UUID.randomUUID();
        NonRetryableOutboxEventException failure = new NonRetryableOutboxEventException("bad payload");
        when(outboxEventRepository.findDuePendingCandidateIds(BATCH_SIZE)).thenReturn(List.of(failedId));
        when(transactionalWorker.processEvent(failedId)).thenThrow(failure);
        when(failureRecorder.recordNonRetryableFailure(failedId, failure)).thenReturn(OutboxEventProcessingOutcome.FAILED);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void processPendingBatch_shouldPassBatchSizeToRepository() {
        int batchSize = 7;
        when(outboxEventRepository.findDuePendingCandidateIds(batchSize)).thenReturn(List.of());

        processor.processPendingBatch(batchSize);

        verify(outboxEventRepository).findDuePendingCandidateIds(batchSize);
    }
}
