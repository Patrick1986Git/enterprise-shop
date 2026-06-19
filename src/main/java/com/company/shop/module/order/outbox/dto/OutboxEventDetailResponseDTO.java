package com.company.shop.module.order.outbox.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.order.outbox.OutboxEventStatus;

public record OutboxEventDetailResponseDTO(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        Instant createdAt,
        Instant processedAt,
        Instant lastAttemptAt,
        int attempts,
        String lastError,
        int requeueCount,
        Instant lastRequeuedAt,
        String lastRequeuedBy) {
}
