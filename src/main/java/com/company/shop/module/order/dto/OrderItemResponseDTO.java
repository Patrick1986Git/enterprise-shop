package com.company.shop.module.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing an order line item.")
public record OrderItemResponseDTO(
    @Schema(description = "Product identifier.", example = "11111111-1111-1111-1111-111111111111", accessMode = Schema.AccessMode.READ_ONLY) UUID productId,
    @Schema(description = "Product display name at the time of response.", example = "Wireless Keyboard", accessMode = Schema.AccessMode.READ_ONLY) String productName,
    @Schema(description = "Product stock keeping unit.", example = "KEYBOARD-001", accessMode = Schema.AccessMode.READ_ONLY) String sku,
    @Schema(description = "Ordered quantity.", example = "2", minimum = "1", accessMode = Schema.AccessMode.READ_ONLY) int quantity,
    @Schema(description = "Unit price for the order item.", example = "79.99", accessMode = Schema.AccessMode.READ_ONLY) BigDecimal price,
    @Schema(description = "Line subtotal calculated from price and quantity.", example = "159.98", accessMode = Schema.AccessMode.READ_ONLY) BigDecimal subtotal
) {}
