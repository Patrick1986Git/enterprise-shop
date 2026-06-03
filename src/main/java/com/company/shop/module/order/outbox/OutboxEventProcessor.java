package com.company.shop.module.order.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final Map<String, OutboxEventHandler> handlersByEventType;

    public OutboxEventProcessor(
            OutboxEventRepository outboxEventRepository,
            List<OutboxEventHandler> handlers) {
        this.outboxEventRepository = outboxEventRepository;
        this.handlersByEventType = buildHandlersByEventType(handlers);
    }

    @Transactional
    public OutboxEventProcessingResult processPendingBatch(int batchSize) {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingBatchForUpdate(batchSize);
        int processedCount = 0;
        int failedCount = 0;

        for (OutboxEvent event : pendingEvents) {
            OutboxEventHandler handler = handlersByEventType.get(event.getEventType());
            if (handler == null) {
                event.markFailed("No outbox handler registered for event type: " + event.getEventType());
                failedCount++;
                continue;
            }

            try {
                handler.handle(event);
                event.markProcessed();
                processedCount++;
            } catch (Exception ex) {
                event.markFailed(errorMessage(ex));
                failedCount++;
            }
        }

        return new OutboxEventProcessingResult(processedCount, failedCount);
    }

    private Map<String, OutboxEventHandler> buildHandlersByEventType(List<OutboxEventHandler> handlers) {
        Map<String, OutboxEventHandler> result = new HashMap<>();
        for (OutboxEventHandler handler : handlers) {
            result.put(handler.eventType(), handler);
        }
        return Map.copyOf(result);
    }

    private String errorMessage(Exception ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex.getClass().getName();
        }
        return ex.getMessage();
    }
}
