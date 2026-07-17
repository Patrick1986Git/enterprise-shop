package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventFailureRecorder {

    static final String MAX_ATTEMPTS_EXCEEDED_REASON = "Max attempts exceeded";
    static final String NON_RETRYABLE_FAILURE_REASON = "Non-retryable processing failure";

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProcessingProperties properties;

    public OutboxEventFailureRecorder(
            OutboxEventRepository outboxEventRepository,
            OutboxProcessingProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEventProcessingOutcome recordRetryableFailure(UUID eventId, Exception exception) {
        return outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)
                .map(event -> recordFailedAttempt(event, errorMessage(exception)))
                .orElse(OutboxEventProcessingOutcome.SKIPPED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEventProcessingOutcome recordNonRetryableFailure(
            UUID eventId,
            NonRetryableOutboxEventException exception) {
        return outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)
                .map(event -> {
                    event.markDeadLetter(errorMessage(exception), NON_RETRYABLE_FAILURE_REASON);
                    return OutboxEventProcessingOutcome.FAILED;
                })
                .orElse(OutboxEventProcessingOutcome.SKIPPED);
    }

    private OutboxEventProcessingOutcome recordFailedAttempt(OutboxEvent event, String errorMessage) {
        if (event.getAttempts() + 1 >= properties.maxAttempts()) {
            event.markDeadLetter(errorMessage, MAX_ATTEMPTS_EXCEEDED_REASON);
            return OutboxEventProcessingOutcome.FAILED;
        }

        event.scheduleRetry(errorMessage, Instant.now().plus(properties.retryDelay()));
        return OutboxEventProcessingOutcome.FAILED;
    }

    private String errorMessage(Exception ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception causeException
                    && causeException.getMessage() != null
                    && !causeException.getMessage().isBlank()) {
                return causeException.getMessage();
            }
            return ex.getClass().getName();
        }
        return ex.getMessage();
    }
}
