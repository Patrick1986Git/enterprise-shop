package com.company.shop.module.order.dto;

import java.time.Instant;
import java.util.UUID;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.entity.StripePaymentConflictReason;

public record StripePaymentConflictResponseDTO(UUID id, UUID orderId, UUID paymentId, String stripeEventId,
        String providerPaymentId, String eventType, OrderStatus orderStatus, PaymentStatus paymentStatus,
        StripePaymentConflictReason reason, Instant observedAt) {
}
