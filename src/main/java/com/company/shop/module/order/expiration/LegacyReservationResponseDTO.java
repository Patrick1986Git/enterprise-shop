package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;

public record LegacyReservationResponseDTO(
        UUID orderId,
        OrderStatus status,
        LocalDateTime createdAt,
        Instant reservationExpiresAt,
        PaymentStatus paymentStatus,
        boolean providerPaymentAttached) {
}
