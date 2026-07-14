package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventProcessor {

    private static final String MAX_ATTEMPTS_EXCEEDED_REASON = "Max attempts exceeded";
    private static final String NON_RETRYABLE_FAILURE_REASON = "Non-retryable processing failure";

    private final OutboxEventRepository outboxEventRepository;
    private final Map<String, OutboxEventHandler> handlersByEventType;
    private final OutboxProcessingProperties properties;

    public OutboxEventProcessor(
            OutboxEventRepository outboxEventRepository,
            List<OutboxEventHandler> handlers,
            OutboxProcessingProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.handlersByEventType = buildHandlersByEventType(handlers);
        this.properties = properties;
    }

    @Transactional
    public OutboxEventProcessingResult processPendingBatch(int batchSize) {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingBatchForUpdate(batchSize);
        int processedCount = 0;
        int failedCount = 0;

        for (OutboxEvent event : pendingEvents) {
            OutboxEventHandler handler = handlersByEventType.get(event.getEventType());
            if (handler == null) {
                recordFailedAttempt(event, "No outbox handler registered for event type: " + event.getEventType());
                failedCount++;
                continue;
            }

            try {
                handler.handle(event);
                event.markProcessed();
                processedCount++;
            } catch (NonRetryableOutboxEventException ex) {
                event.markDeadLetter(errorMessage(ex), NON_RETRYABLE_FAILURE_REASON);
                failedCount++;
            } catch (Exception ex) {
                recordFailedAttempt(event, errorMessage(ex));
                failedCount++;
            }
        }

        return new OutboxEventProcessingResult(processedCount, failedCount);
    }

    private void recordFailedAttempt(OutboxEvent event, String errorMessage) {
        if (event.getAttempts() + 1 >= properties.maxAttempts()) {
            event.markDeadLetter(errorMessage, MAX_ATTEMPTS_EXCEEDED_REASON);
            return;
        }

        Instant nextAttemptAt = Instant.now().plus(properties.retryDelay());
        event.scheduleRetry(errorMessage, nextAttemptAt);
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
