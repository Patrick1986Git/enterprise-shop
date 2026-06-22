package com.company.shop.module.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing a cart line item.")
public record CartItemResponseDTO(
        @Schema(description = "Product identifier.", example = "11111111-1111-1111-1111-111111111111", accessMode = Schema.AccessMode.READ_ONLY) UUID productId,
        @Schema(description = "Product display name.", example = "Wireless Keyboard", accessMode = Schema.AccessMode.READ_ONLY) String productName,
        @Schema(description = "URL-friendly product identifier.", example = "wireless-keyboard", accessMode = Schema.AccessMode.READ_ONLY) String productSlug,
        @Schema(description = "Primary image URL for the product.", example = "https://cdn.example.com/products/keyboard-001.jpg", accessMode = Schema.AccessMode.READ_ONLY) String mainImageUrl,
        @Schema(description = "Current unit price.", example = "79.99", accessMode = Schema.AccessMode.READ_ONLY) BigDecimal unitPrice,
        @Schema(description = "Quantity currently in the cart.", example = "2", minimum = "1", accessMode = Schema.AccessMode.READ_ONLY) int quantity,
        @Schema(description = "Line subtotal calculated from unit price and quantity.", example = "159.98", accessMode = Schema.AccessMode.READ_ONLY) BigDecimal subtotal,
        @Schema(description = "Current available stock for the product.", example = "25", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY) int stockAvailable,
        @Schema(description = "Whether the product stock is considered low.", example = "false", accessMode = Schema.AccessMode.READ_ONLY) boolean isLowStock
) {}
