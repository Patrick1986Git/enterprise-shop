/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Data Transfer Object representing a request to add a product to the shopping cart.
 * <p>
 * This DTO is typically used in POST requests to the cart endpoints. It ensures 
 * that the incoming data contains a valid product identifier and a positive quantity.
 * </p>
 *
 * @param productId Unique identifier of the product to be added to the cart.
 * @param quantity  Number of items to add. Must be at least 1.
 * @since 1.0.0
 */
public record AddToCartRequestDTO(
        @NotNull(message = "{validation.cart.product.required}") 
        UUID productId,
        
        @NotNull(message = "{validation.cart.quantity.required}")
        @Min(value = 1, message = "{validation.cart.quantity.min}") 
        Integer quantity
) {}
