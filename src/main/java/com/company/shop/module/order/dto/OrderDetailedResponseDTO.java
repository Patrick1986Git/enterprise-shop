package com.company.shop.module.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.company.shop.module.order.entity.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing detailed order information.")
public record OrderDetailedResponseDTO(
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
            description = "Email address of the user who placed the order.",
            example = "user@example.com",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    String userEmail,
    @Schema(
            description = "Items included in the order.",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    List<OrderItemResponseDTO> items
) {}
