package com.company.shop.module.cart.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request payload for adding a product to the cart.")
public record AddToCartRequestDTO(
        @Schema(description = "Identifier of the product to add.", example = "11111111-1111-1111-1111-111111111111", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "{validation.cart.product.required}") UUID productId,
        @Schema(description = "Quantity to add to the cart.", example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "{validation.cart.quantity.required}") @Min(value = 1, message = "{validation.cart.quantity.min}") Integer quantity
) {}
