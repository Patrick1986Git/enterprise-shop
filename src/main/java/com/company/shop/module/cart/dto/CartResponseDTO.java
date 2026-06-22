/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Data Transfer Object representing the complete state of a shopping cart.
 * <p>
 * This DTO aggregates all line items and provides summary calculations
 * such as total value and total item count. It serves as the primary
 * response object for cart-related operations.
 * </p>
 *
 * @param id               Unique identifier of the cart.
 * @param items            List of detailed item information within the cart.
 * @param totalAmount      Sum of all item subtotals (gross total).
 * @param totalItemsCount  Aggregate count of all product units in the cart.
 * @since 1.0.0
 */
@Schema(description = "Response payload containing the complete cart state.")
public record CartResponseDTO(
        @Schema(
                description = "Unique cart identifier.",
                example = "33333333-3333-3333-3333-333333333333",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID id,
        @Schema(
                description = "Items currently in the cart.",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        List<CartItemResponseDTO> items,
        @Schema(
                description = "Total monetary amount for all cart items.",
                example = "159.98",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        BigDecimal totalAmount,
        @Schema(
                description = "Total number of product units in the cart.",
                example = "2",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        int totalItemsCount
) {}
