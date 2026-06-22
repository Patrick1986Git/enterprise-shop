package com.company.shop.module.cart.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing the complete cart state.")
public record CartResponseDTO(
        @Schema(description = "Unique cart identifier.", example = "33333333-3333-3333-3333-333333333333", accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @Schema(description = "Items currently in the cart.", accessMode = Schema.AccessMode.READ_ONLY) List<CartItemResponseDTO> items,
        @Schema(description = "Total monetary amount for all cart items.", example = "159.98", accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalAmount,
        @Schema(description = "Total number of product units in the cart.", example = "2", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY) int totalItemsCount
) {}
