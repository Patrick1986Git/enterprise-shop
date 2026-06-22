/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Data Transfer Object representing a single line item in the shopping cart.
 * <p>
 * This DTO provides comprehensive information about a product in the cart,
 * including pricing calculations and current stock status to inform the user
 * about product availability.
 * </p>
 *
 * @param productId      Unique identifier of the product.
 * @param productName    Display name of the product.
 * @param productSlug    SEO-friendly URL identifier.
 * @param mainImageUrl   Primary image resource location for the product thumbnail.
 * @param unitPrice      Current price of a single unit of the product.
 * @param quantity       The number of units present in the cart.
 * @param subtotal       Calculated total for this item (unitPrice * quantity).
 * @param stockAvailable Current quantity available in the warehouse.
 * @param isLowStock     Flag indicating if the stock level is below the business threshold.
 * * @since 1.0.0
 */
@Schema(description = "Response payload containing a cart line item.")
public record CartItemResponseDTO(
        @Schema(
                description = "Product identifier.",
                example = "11111111-1111-1111-1111-111111111111",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID productId,
        @Schema(
                description = "Product display name.",
                example = "Wireless Keyboard",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String productName,
        @Schema(
                description = "URL-friendly product identifier.",
                example = "wireless-keyboard",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String productSlug,
        @Schema(
                description = "Primary image URL for the product.",
                example = "https://cdn.example.com/products/keyboard-001.jpg",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String mainImageUrl,
        @Schema(
                description = "Current unit price.",
                example = "79.99",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        BigDecimal unitPrice,
        @Schema(
                description = "Quantity currently in the cart.",
                example = "2",
                minimum = "1",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        int quantity,
        @Schema(
                description = "Line subtotal calculated from unit price and quantity.",
                example = "159.98",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        BigDecimal subtotal,
        @Schema(
                description = "Current available stock for the product.",
                example = "25",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        int stockAvailable,
        @Schema(
                description = "Whether the product stock is considered low.",
                example = "false",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        boolean isLowStock
) {}
