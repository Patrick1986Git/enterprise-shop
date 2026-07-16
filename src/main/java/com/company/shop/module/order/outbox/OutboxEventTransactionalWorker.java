package com.company.shop.module.order.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventTransactionalWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final Map<String, OutboxEventHandler> handlersByEventType;

    public OutboxEventTransactionalWorker(
            OutboxEventRepository outboxEventRepository,
            List<OutboxEventHandler> handlers) {
        this.outboxEventRepository = outboxEventRepository;
        this.handlersByEventType = buildHandlersByEventType(handlers);
    }

    @Transactional
    public OutboxEventProcessingOutcome processEvent(UUID eventId) {
        return outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)
                .map(this::processLockedEvent)
                .orElse(OutboxEventProcessingOutcome.SKIPPED);
    }

    private OutboxEventProcessingOutcome processLockedEvent(OutboxEvent event) {
        OutboxEventHandler handler = handlersByEventType.get(event.getEventType());
        if (handler == null) {
            throw new IllegalStateException("No outbox handler registered for event type: " + event.getEventType());
        }

        handler.handle(event);
        event.markProcessed();
        return OutboxEventProcessingOutcome.PROCESSED;
    }

    private Map<String, OutboxEventHandler> buildHandlersByEventType(List<OutboxEventHandler> handlers) {
        Map<String, OutboxEventHandler> result = new HashMap<>();
        for (OutboxEventHandler handler : handlers) {
            String eventType = handler.eventType();
            validateEventType(handler, eventType);

            OutboxEventHandler existingHandler = result.putIfAbsent(eventType, handler);
            if (existingHandler != null) {
                throw new IllegalStateException(
                        "Duplicate outbox handler registration for event type '" + eventType + "': "
                                + existingHandler.getClass().getName() + " and "
                                + handler.getClass().getName() + ".");
            }
        }
        return Map.copyOf(result);
    }

    private void validateEventType(OutboxEventHandler handler, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalStateException(
                    "Outbox handler " + handler.getClass().getName() + " must declare a nonblank event type.");
        }
        if (!eventType.equals(eventType.strip())) {
            throw new IllegalStateException(
                    "Outbox handler " + handler.getClass().getName()
                            + " must declare an event type without leading or trailing whitespace.");
        }
    }
}
