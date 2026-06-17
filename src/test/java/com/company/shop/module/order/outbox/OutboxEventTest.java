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
        assertThat(event.getRequeueCount()).isZero();
        assertThat(event.getLastRequeuedAt()).isNull();
        assertThat(event.getLastRequeuedBy()).isNull();
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
    void requeueForProcessing_shouldChangeFailedEventToPendingClearFailureStateAndPreserveAttempts() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        event.markFailed("first failure");
        event.markFailed("second failure");
        event.markProcessed();
        event.markFailed("third failure");
        int attempts = event.getAttempts();

        event.requeueForProcessing("admin@example.com");

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getProcessedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(attempts);
        assertThat(event.getRequeueCount()).isEqualTo(1);
        assertThat(event.getLastRequeuedAt()).isNotNull();
        assertThat(event.getLastRequeuedBy()).isEqualTo("admin@example.com");
    }

    @Test
    void requeueForProcessing_shouldIncrementCountAndUpdateActorForMultipleRequeues() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        event.markFailed("first failure");
        event.requeueForProcessing("first-admin@example.com");
        event.markFailed("second failure");

        event.requeueForProcessing(" second-admin@example.com ");

        assertThat(event.getRequeueCount()).isEqualTo(2);
        assertThat(event.getLastRequeuedAt()).isNotNull();
        assertThat(event.getLastRequeuedBy()).isEqualTo("second-admin@example.com");
        assertThat(event.getAttempts()).isEqualTo(2);
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
