/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Data Transfer Object representing a request to create a new order.
 * <p>
 * This DTO encapsulates all necessary information required from the client
 * to initiate the ordering process, including product selection and optional
 * promotional codes.
 * </p>
 *
 * @since 1.0.0
 */
@Schema(description = "Request payload for creating a new order.")
public class OrderCreateRequestDTO {

    @Schema(
            description = "Items to include in the order.",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotEmpty(message = "{validation.order.items.required}")
    @Valid
    private List<OrderItemRequestDTO> items;

    @Schema(
            description = "Optional promotional discount code.",
            example = "SAVE10",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String discountCode;

    public List<OrderItemRequestDTO> getItems() {
        return items;
    }

    public String getDiscountCode() {
        return discountCode;
    }

    @Schema(description = "Request payload for an individual order item.")
    public static class OrderItemRequestDTO {
        @Schema(
                description = "Identifier of the product to order.",
                example = "11111111-1111-1111-1111-111111111111",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "{validation.order.item.product.required}")
        private UUID productId;

        @Schema(
                description = "Quantity of the product to order.",
                example = "2",
                minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @Min(value = 1, message = "{validation.order.item.quantity.min}")
        private int quantity;

        public UUID getProductId() {
            return productId;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
