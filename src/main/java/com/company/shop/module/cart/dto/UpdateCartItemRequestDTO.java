/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Data Transfer Object representing a request to update an existing cart item's quantity.
 * <p>
 * This DTO is used in PUT or PATCH operations where the target product is already 
 * identified by a path variable, and only the new absolute quantity needs to be provided.
 * </p>
 *
 * @param quantity The new absolute number of units for the product. Must be at least 1.
 * @since 1.0.0
 */
public record UpdateCartItemRequestDTO(
        @NotNull(message = "{validation.cart.quantity.required}")
        @Min(value = 1, message = "{validation.cart.quantity.min}") 
        Integer quantity
) {}
