package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.UUID;

public record ReservationExpirationRecoveryResult(
        UUID workId, UUID orderId, ReservationExpirationWorkStatus status, int attempts,
        int recoveryCount, Instant lastRecoveredAt) {}
