package com.company.shop.module.order.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Append-only administrator investigation action for Stripe conflict evidence.")
public record StripePaymentConflictDispositionResponseDTO(
        UUID id,
        UUID conflictId,
        StripePaymentConflictDispositionType actionType,
        String actorEmail,
        Instant createdAt) {
}
