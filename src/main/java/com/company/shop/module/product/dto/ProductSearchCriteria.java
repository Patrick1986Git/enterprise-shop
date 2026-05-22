/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Record representing the search and filtering criteria for products.
 * <p>
 * This DTO is used to capture user input from search forms or API query parameters.
 * It supports full-text search queries, category filtering, price ranges, 
 * and minimum rating thresholds.
 * </p>
 *
 * @param query      The full-text search string (supports Polish linguistic rules).
 * @param categoryId Unique identifier of the category to filter by.
 * @param minPrice   Minimum product price (must be zero or positive).
 * @param maxPrice   Maximum product price (must be zero or positive).
 * @param minRating  Minimum average customer rating (scale 0-5).
 * * @since 1.0.0
 */
public record ProductSearchCriteria(
        String query,
        UUID categoryId,
        
        @PositiveOrZero(message = "{validation.product.price.min.positiveOrZero}")
        BigDecimal minPrice,
        
        @PositiveOrZero(message = "{validation.product.price.max.positiveOrZero}")
        BigDecimal maxPrice,
        
        @Min(value = 0, message = "{validation.product.rating.min}") @Max(value = 5, message = "{validation.product.rating.max}")
        Double minRating
) {}