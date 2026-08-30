package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.UUID;

public record ReservationExpirationWorkResponseDTO(
        UUID id,
        UUID orderId,
        ReservationExpirationWorkStatus status,
        Instant dueAt,
        Instant nextAttemptAt,
        Instant claimUntil,
        int attempts,
        String lastError,
        Instant completedAt,
        Instant failedAt,
        int recoveryCount,
        Instant lastRecoveredAt,
        String lastRecoveredBy) {
}
