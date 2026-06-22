package com.company.shop.module.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.company.shop.module.order.entity.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing order summary details.")
public record OrderResponseDTO(
        @Schema(
                description = "Unique order identifier.",
                example = "44444444-4444-4444-4444-444444444444",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID id,
        @Schema(
                description = "Current order status.",
                example = "PENDING",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        OrderStatus status,
        @Schema(
                description = "Total order amount.",
                example = "159.98",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        BigDecimal totalAmount,
        @Schema(
                description = "Date and time when the order was created.",
                example = "2026-06-22T10:15:30",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        LocalDateTime createdAt,
        @Schema(
                description = "Payment intent details, when payment is required.",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        PaymentIntentResponseDTO paymentInfo
) {}
