package com.company.shop.module.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request payload for updating a cart item quantity.")
public record UpdateCartItemRequestDTO(
        @Schema(description = "New absolute quantity for the cart item.", example = "3", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "{validation.cart.quantity.required}") @Min(value = 1, message = "{validation.cart.quantity.min}") Integer quantity
) {}
