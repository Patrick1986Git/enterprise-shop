package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Immutable record of a committed reservation-expiration admin action.")
public record ReservationExpirationAdminActionLogResponseDTO(
        UUID id,
        UUID orderId,
        UUID workId,
        ReservationExpirationAdminActionType actionType,
        ReservationExpirationAdminActionOutcome outcome,
        String actorEmail,
        Instant createdAt) {
}
