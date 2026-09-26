package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxEventFailureRecorderTest {

    private static final Instant ATTEMPT_TIME = Instant.parse("2026-09-07T12:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxProcessingProperties properties;
    private OutboxEventFailureRecorder recorder;

    @BeforeEach
    void setUp() {
        properties = new OutboxProcessingProperties();
        properties.setRetryDelay(Duration.ofSeconds(30));
        recorder = new OutboxEventFailureRecorder(outboxEventRepository, properties);
    }

    @Test
    void recordRetryableFailure_shouldScheduleRetryBeforeMaxAttempts() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));
        when(outboxEventRepository.findCurrentTimestamp()).thenReturn(ATTEMPT_TIME);

        OutboxEventProcessingOutcome outcome = recorder.recordRetryableFailure(eventId, new IllegalStateException("handler failed"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("handler failed");
        assertThat(event.getLastAttemptAt()).isEqualTo(ATTEMPT_TIME);
        assertThat(event.getNextAttemptAt()).isEqualTo(ATTEMPT_TIME.plusSeconds(30));
        assertThat(event.getDeadLetterReason()).isNull();
    }

    @Test
    void recordRetryableFailure_shouldDeadLetterAtMaxAttempts() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent();
        event.scheduleRetry("first", Instant.parse("2026-01-01T00:00:00Z"), Instant.now().minusSeconds(60));
        event.scheduleRetry("second", Instant.parse("2026-01-01T00:00:00Z"), Instant.now().minusSeconds(60));
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));
        when(outboxEventRepository.findCurrentTimestamp()).thenReturn(ATTEMPT_TIME);

        OutboxEventProcessingOutcome outcome = recorder.recordRetryableFailure(eventId, new IllegalStateException("final failure"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastError()).isEqualTo("final failure");
        assertThat(event.getDeadLetterReason()).isEqualTo("Max attempts exceeded");
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getLastAttemptAt()).isEqualTo(ATTEMPT_TIME);
    }

    @Test
    void recordNonRetryableFailure_shouldDeadLetterImmediately() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));
        when(outboxEventRepository.findCurrentTimestamp()).thenReturn(ATTEMPT_TIME);

        OutboxEventProcessingOutcome outcome = recorder.recordNonRetryableFailure(
                eventId,
                new NonRetryableOutboxEventException("bad payload"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("bad payload");
        assertThat(event.getDeadLetterReason()).isEqualTo("Non-retryable processing failure");
        assertThat(event.getLastAttemptAt()).isEqualTo(ATTEMPT_TIME);
    }

    @Test
    void recordFailure_shouldSkipAfterAnotherProcessorChangedEvent() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.empty());

        OutboxEventProcessingOutcome outcome = recorder.recordRetryableFailure(eventId, new IllegalStateException("failed"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.SKIPPED);
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
    }
}
