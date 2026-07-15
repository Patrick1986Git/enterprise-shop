package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    private OutboxProcessingProperties properties;
    private OutboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        when(handler.eventType()).thenReturn("OrderPlaced");
        properties = new OutboxProcessingProperties();
        processor = new OutboxEventProcessor(outboxEventRepository, List.of(handler), properties);
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
    void constructor_shouldAcceptMultipleHandlersWithDifferentEventTypes() {
        RecordingHandler orderPlacedHandler = new RecordingHandler("OrderPlaced");
        RecordingHandler orderPaidHandler = new RecordingHandler("OrderPaid");
        OutboxEvent orderPlacedEvent = pendingEvent("OrderPlaced");
        OutboxEvent orderPaidEvent = pendingEvent("OrderPaid");
        OutboxEventProcessor processorWithMultipleHandlers = new OutboxEventProcessor(
                outboxEventRepository,
                List.of(orderPlacedHandler, orderPaidHandler),
                properties);
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE))
                .thenReturn(List.of(orderPlacedEvent, orderPaidEvent));

        OutboxEventProcessingResult result = processorWithMultipleHandlers.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(orderPlacedHandler.handledEvents).containsExactly(orderPlacedEvent);
        assertThat(orderPaidHandler.handledEvents).containsExactly(orderPaidEvent);
    }

    @Test
    void constructor_shouldFailWhenDuplicateEventTypesAreRegistered() {
        OutboxEventHandler existingHandler = new ExistingOrderPlacedHandler();
        OutboxEventHandler conflictingHandler = new ConflictingOrderPlacedHandler();

        assertThatThrownBy(() -> new OutboxEventProcessor(
                outboxEventRepository,
                List.of(existingHandler, conflictingHandler),
                properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate outbox handler registration for event type 'OrderPlaced'")
                .hasMessageContaining(ExistingOrderPlacedHandler.class.getName())
                .hasMessageContaining(ConflictingOrderPlacedHandler.class.getName());
    }

    @Test
    void constructor_shouldRejectNullEventType() {
        assertInvalidEventType(new RecordingHandler(null), "must declare a nonblank event type");
    }

    @Test
    void constructor_shouldRejectEmptyEventType() {
        assertInvalidEventType(new RecordingHandler(""), "must declare a nonblank event type");
    }

    @Test
    void constructor_shouldRejectBlankEventType() {
        assertInvalidEventType(new RecordingHandler("   "), "must declare a nonblank event type");
    }

    @Test
    void constructor_shouldRejectEventTypeWithLeadingWhitespace() {
        assertInvalidEventType(new RecordingHandler(" OrderPlaced"), "without leading or trailing whitespace");
    }

    @Test
    void constructor_shouldRejectEventTypeWithTrailingWhitespace() {
        assertInvalidEventType(new RecordingHandler("OrderPlaced "), "without leading or trailing whitespace");
    }

    @Test
    void constructor_shouldKeepRoutingImmutableAfterSuccessfulRegistration() {
        RecordingHandler orderPlacedHandler = new RecordingHandler("OrderPlaced");
        List<OutboxEventHandler> handlers = new ArrayList<>();
        handlers.add(orderPlacedHandler);
        OutboxEventProcessor processorWithMutableHandlerSource = new OutboxEventProcessor(
                outboxEventRepository,
                handlers,
                properties);
        handlers.clear();
        handlers.add(new RecordingHandler("OrderPaid"));
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        OutboxEventProcessingResult result = processorWithMutableHandlerSource.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(orderPlacedHandler.handledEvents).containsExactly(event);
    }

    @Test
    void processPendingBatch_shouldMarkEventAsProcessedOnHandlerSuccess() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        processor.processPendingBatch(BATCH_SIZE);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getLastAttemptAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void processPendingBatch_shouldDeadLetterImmediatelyWhenHandlerThrowsNonRetryableException() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));
        doThrow(new NonRetryableOutboxEventException("deterministic contract failure")).when(handler).handle(event);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("deterministic contract failure");
        assertThat(event.getLastAttemptAt()).isNotNull();
        assertThat(event.getDeadLetterReason()).isEqualTo("Non-retryable processing failure");
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getProcessedAt()).isNull();
    }

    @Test
    void processPendingBatch_shouldScheduleRetryAndStoreLastErrorWhenHandlerThrowsBeforeMaxAttempts() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("handler failed")).when(handler).handle(event);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getLastError()).isEqualTo("handler failed");
        assertThat(event.getLastAttemptAt()).isNotNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isNotNull();
        assertThat(event.getDeadLetterReason()).isNull();
    }

    @Test
    void processPendingBatch_shouldApplyRetryPolicyWhenNoHandlerExists() {
        OutboxEvent event = pendingEvent("OrderPaid");
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(handler, never()).handle(event);
        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getLastError()).isEqualTo("No outbox handler registered for event type: OrderPaid");
        assertThat(event.getLastAttemptAt()).isNotNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isNotNull();
        assertThat(event.getDeadLetterReason()).isNull();
    }

    @Test
    void processPendingBatch_shouldMarkEventAsDeadLetterOnFinalHandlerFailure() {
        OutboxEvent event = pendingEvent("OrderPlaced");
        event.scheduleRetry("first failure", Instant.now().minus(Duration.ofMinutes(1)));
        event.scheduleRetry("second failure", Instant.now().minus(Duration.ofMinutes(1)));
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("final failure")).when(handler).handle(event);

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastError()).isEqualTo("final failure");
        assertThat(event.getDeadLetterReason()).isEqualTo("Max attempts exceeded");
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getProcessedAt()).isNull();
    }

    @Test
    void processPendingBatch_shouldUseConfiguredRetryDelayWhenSchedulingRetry() {
        properties.setRetryDelay(Duration.ofSeconds(30));
        OutboxEvent event = pendingEvent("OrderPlaced");
        Instant beforeProcessing = Instant.now();
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("handler failed")).when(handler).handle(event);

        processor.processPendingBatch(BATCH_SIZE);

        assertThat(event.getNextAttemptAt()).isBetween(
                beforeProcessing.plusSeconds(30),
                Instant.now().plusSeconds(30));
    }

    @Test
    void processPendingBatch_shouldApplyDeadLetterPolicyToUnknownEventTypeAtMaxAttempts() {
        OutboxEvent event = pendingEvent("OrderPaid");
        event.scheduleRetry("first failure", Instant.now().minus(Duration.ofMinutes(1)));
        event.scheduleRetry("second failure", Instant.now().minus(Duration.ofMinutes(1)));
        when(outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE)).thenReturn(List.of(event));

        OutboxEventProcessingResult result = processor.processPendingBatch(BATCH_SIZE);

        verify(handler, never()).handle(event);
        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastError()).isEqualTo("No outbox handler registered for event type: OrderPaid");
        assertThat(event.getDeadLetterReason()).isEqualTo("Max attempts exceeded");
        assertThat(event.getNextAttemptAt()).isNull();
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

    private void assertInvalidEventType(OutboxEventHandler invalidHandler, String expectedMessage) {
        assertThatThrownBy(() -> new OutboxEventProcessor(outboxEventRepository, List.of(invalidHandler), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(invalidHandler.getClass().getName())
                .hasMessageContaining(expectedMessage);
    }

    private OutboxEvent pendingEvent(String eventType) {
        return OutboxEvent.pending("Order", UUID.randomUUID(), eventType, "{}");
    }

    private static class RecordingHandler implements OutboxEventHandler {

        private final String eventType;
        private final List<OutboxEvent> handledEvents = new ArrayList<>();

        private RecordingHandler(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(OutboxEvent event) {
            handledEvents.add(event);
        }
    }

    private static class ExistingOrderPlacedHandler extends RecordingHandler {

        private ExistingOrderPlacedHandler() {
            super("OrderPlaced");
        }
    }

    private static class ConflictingOrderPlacedHandler extends RecordingHandler {

        private ConflictingOrderPlacedHandler() {
            super("OrderPlaced");
        }
    }
}
