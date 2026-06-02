package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxEventTest {

    @Test
    void pending_shouldCreatePendingEventWithZeroAttempts() {
        UUID aggregateId = UUID.randomUUID();

        OutboxEvent event = OutboxEvent.pending("Order", aggregateId, "OrderPlaced", "{}");

        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(aggregateId);
        assertThat(event.getEventType()).isEqualTo("OrderPlaced");
        assertThat(event.getPayload()).isEqualTo("{}");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getProcessedAt()).isNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void markProcessed_shouldSetProcessedStatusAndProcessedAtAndClearLastError() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        event.markFailed("temporary failure");

        event.markProcessed();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void markFailed_shouldSetFailedStatusIncrementAttemptsStoreLastErrorAndKeepProcessedAtNull() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");

        event.markFailed("publisher unavailable");

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("publisher unavailable");
        assertThat(event.getProcessedAt()).isNull();
    }
}
