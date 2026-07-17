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
        Instant beforeRecording = Instant.now();
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));

        OutboxEventProcessingOutcome outcome = recorder.recordRetryableFailure(eventId, new IllegalStateException("handler failed"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("handler failed");
        assertThat(event.getNextAttemptAt()).isBetween(beforeRecording.plusSeconds(30), Instant.now().plusSeconds(30));
        assertThat(event.getDeadLetterReason()).isNull();
    }

    @Test
    void recordRetryableFailure_shouldDeadLetterAtMaxAttempts() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent();
        event.scheduleRetry("first", Instant.now().minusSeconds(60));
        event.scheduleRetry("second", Instant.now().minusSeconds(60));
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));

        OutboxEventProcessingOutcome outcome = recorder.recordRetryableFailure(eventId, new IllegalStateException("final failure"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastError()).isEqualTo("final failure");
        assertThat(event.getDeadLetterReason()).isEqualTo("Max attempts exceeded");
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void recordNonRetryableFailure_shouldDeadLetterImmediately() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findDuePendingByIdForUpdateSkipLocked(eventId)).thenReturn(Optional.of(event));

        OutboxEventProcessingOutcome outcome = recorder.recordNonRetryableFailure(
                eventId,
                new NonRetryableOutboxEventException("bad payload"));

        assertThat(outcome).isEqualTo(OutboxEventProcessingOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("bad payload");
        assertThat(event.getDeadLetterReason()).isEqualTo("Non-retryable processing failure");
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
