/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object representing a request to finalize the checkout process.
 * <p>
 * This record captures optional customer inputs provided during the final stage
 * of order placement, such as promotional codes and delivery instructions.
 * </p>
 *
 * @param discountCode  an optional alphanumeric code for applying price reductions.
 * Validated to prevent injection of excessively long strings.
 * @param customerNotes additional instructions or comments for the fulfillment team.
 * Useful for delivery details or gift messages.
 * @since 1.0.0
 */
@Schema(description = "Request payload for finalizing checkout.")
public record OrderCheckoutRequestDTO(
    @Schema(description = "Optional promotional discount code.", example = "SAVE10", minLength = 3, maxLength = 20, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(min = 3, max = 20, message = "{validation.order.discountCode.size}") String discountCode,
    @Schema(description = "Optional customer notes for fulfillment.", example = "Leave the package at reception.", maxLength = 500, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(max = 500, message = "{validation.order.customerNotes.size}") String customerNotes
) {}
