package com.company.shop.module.order.outbox.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.order.outbox.OutboxEventAdminActionType;

public record OutboxEventAdminActionLogResponseDTO(
        UUID id,
        UUID outboxEventId,
        OutboxEventAdminActionType actionType,
        String actorEmail,
        Instant createdAt,
        String details) {
}
