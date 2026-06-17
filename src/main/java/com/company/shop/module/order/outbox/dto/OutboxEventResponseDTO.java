package com.company.shop.module.order.outbox.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.order.outbox.OutboxEventStatus;

public record OutboxEventResponseDTO(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        OutboxEventStatus status,
        Instant createdAt,
        Instant processedAt,
        int attempts,
        String lastError,
        int requeueCount,
        Instant lastRequeuedAt,
        String lastRequeuedBy) {
}
