package com.company.shop.module.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for finalizing checkout.")
public record OrderCheckoutRequestDTO(
    @Schema(description = "Optional promotional discount code.", example = "SAVE10", minLength = 3, maxLength = 20, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(min = 3, max = 20, message = "{validation.order.discountCode.size}") String discountCode,
    @Schema(description = "Optional customer notes for fulfillment.", example = "Leave the package at reception.", maxLength = 500, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(max = 500, message = "{validation.order.customerNotes.size}") String customerNotes
) {}
