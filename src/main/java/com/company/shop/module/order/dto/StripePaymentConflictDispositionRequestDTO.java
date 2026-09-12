package com.company.shop.module.order.dto;

import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Admin disposition to append to immutable Stripe conflict evidence.")
public record StripePaymentConflictDispositionRequestDTO(
        @NotNull
        @Schema(description = "Investigation action; it never changes financial state.", example = "ACKNOWLEDGED")
        StripePaymentConflictDispositionType actionType) {
}
