package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.UUID;

public record LegacyReservationAdoptionResult(
        UUID orderId,
        UUID workId,
        Instant reservationExpiresAt,
        ReservationExpirationWorkStatus workStatus,
        boolean adopted) {
}
