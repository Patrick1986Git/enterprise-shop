package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxEventTransactionalWorkerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private RecordingHandler handler;
    private OutboxEventTransactionalWorker worker;

    @BeforeEach
    void setUp() {
        handler = new RecordingHandler("OrderPlaced");
        worker = new OutboxEventTransactionalWorker(outboxEventRepository, List.of(handler));
    }

    @Test
    void processEvent_shouldMarkEventProcessedOnHandlerSuccess() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));

        OutboxEventProcessingOutcome outcome = worker.processEvent(eventId);

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.PROCESSED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(handler.handledEvents).containsExactly(event);
    }

    @Test
    void processEvent_shouldSkipWhenEventIsMissingOrNotEligibleOrLocked() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.empty());

        OutboxEventProcessingOutcome outcome = worker.processEvent(eventId);

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.SKIPPED);
        verify(outboxEventRepository).findDuePendingByIdForUpdateSkipLocked(eventId);
        verifyNoHandlerCall();
    }

    @Test
    void processEvent_shouldApplyRetryableUnknownEventTypePolicyByThrowing() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent("OrderPaid");
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> worker.processEvent(eventId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No outbox handler registered for event type: OrderPaid");
    }

    @Test
    void constructor_shouldFailWhenDuplicateEventTypesAreRegistered() {
        assertThatThrownBy(() -> new OutboxEventTransactionalWorker(
                outboxEventRepository,
                List.of(new RecordingHandler("OrderPlaced"), new RecordingHandler("OrderPlaced"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate outbox handler registration for event type 'OrderPlaced'");
    }

    @Test
    void constructor_shouldRejectInvalidEventTypes() {
        assertInvalidEventType(null, "must declare a nonblank event type");
        assertInvalidEventType("", "must declare a nonblank event type");
        assertInvalidEventType("   ", "must declare a nonblank event type");
        assertInvalidEventType(" OrderPlaced", "without leading or trailing whitespace");
        assertInvalidEventType("OrderPlaced ", "without leading or trailing whitespace");
    }

    @Test
    void constructor_shouldKeepRoutingImmutableAfterSuccessfulRegistration() {
        List<OutboxEventHandler> handlers = new ArrayList<>();
        handlers.add(handler);
        OutboxEventTransactionalWorker immutableWorker = new OutboxEventTransactionalWorker(outboxEventRepository, handlers);
        handlers.clear();
        handlers.add(new RecordingHandler("OrderPaid"));
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent("OrderPlaced");
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));

        immutableWorker.processEvent(eventId);

        assertThat(handler.handledEvents).containsExactly(event);
    }

    private void assertInvalidEventType(String eventType, String expectedMessage) {
        assertThatThrownBy(() -> new OutboxEventTransactionalWorker(outboxEventRepository, List.of(new RecordingHandler(eventType))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private void verifyNoHandlerCall() {
        assertThat(handler.handledEvents).isEmpty();
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
}
