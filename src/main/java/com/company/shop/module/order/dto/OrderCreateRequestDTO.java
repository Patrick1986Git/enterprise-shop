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
public class OrderCreateRequestDTO {

    /**
     * List of items to be included in the order.
     * Must not be empty and each item will be validated individually.
     */
    @NotEmpty
    @Valid
    private List<OrderItemRequestDTO> items;

    /**
     * Optional promotional discount code to be applied to the order.
     */
    private String discountCode;

    public List<OrderItemRequestDTO> getItems() {
        return items;
    }

    public String getDiscountCode() {
        return discountCode;
    }

    /**
     * Represents an individual item within an order request.
     */
    public static class OrderItemRequestDTO {
        
        /**
         * Unique identifier of the product.
         */
        @NotNull
        private UUID productId;

        /**
         * The number of units for the specified product.
         */
        @Min(1)
        private int quantity;

        public UUID getProductId() {
            return productId;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
