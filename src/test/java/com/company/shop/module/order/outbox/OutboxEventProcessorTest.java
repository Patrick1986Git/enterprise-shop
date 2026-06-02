package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    private OutboxEventHandler handler;

    private OutboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        when(handler.eventType()).thenReturn("OrderPlaced");
        processor = new OutboxEventProcessor(outboxEventRepository, List.of(handler));
    }

    @Test
    void processPendingBatch_shouldProcessPendingEventWhenMatchingHandlerExists() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(handler).handle(event);
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldMarkEventAsProcessedOnHandlerSuccess() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        processor.processPendingBatch(BATCH_SIZE);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void processPendingBatch_shouldMarkEventAsFailedAndStoreLastErrorWhenHandlerThrows() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("handler failed")).when(handler).handle(event);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getLastError()).isEqualTo("handler failed");
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void processPendingBatch_shouldMarkEventAsFailedWhenNoHandlerExists() {
        OutboxEvent event = pendingEvent("OrderPaid");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(handler, never()).handle(event);
        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getLastError()).isEqualTo("No outbox handler registered for event type: OrderPaid");
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void processPendingBatch_shouldRespectEmptyPendingBatch() {
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of());

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void processPendingBatch_shouldPassBatchSizeToRepository() {
        int batchSize = 7;
        when(outboxEventRepository.findPendingBatchForUpdate(batchSize)).thenReturn(List.of());

        processor.processPendingBatch(batchSize);

        verify(outboxEventRepository).findPendingBatchForUpdate(batchSize);
    }

    private OutboxEvent pendingEvent(String eventType) {
        return OutboxEvent.pending("Order", UUID.randomUUID(), eventType, "{}");
    }
}
