package com.company.shop.module.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "Search and filtering criteria for product listings.")
public record ProductSearchCriteria(
        @Schema(description = "Full-text search term matched against product content.", example = "keyboard", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String query,
        @Schema(description = "Category identifier used to filter products.", example = "11111111-1111-1111-1111-111111111111", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        UUID categoryId,
        @Schema(description = "Minimum product price filter.", example = "10.00", minimum = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PositiveOrZero(message = "{validation.product.price.min.positiveOrZero}") BigDecimal minPrice,
        @Schema(description = "Maximum product price filter.", example = "250.00", minimum = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PositiveOrZero(message = "{validation.product.price.max.positiveOrZero}") BigDecimal maxPrice,
        @Schema(description = "Minimum average rating filter on a 0 to 5 scale.", example = "4.0", minimum = "0", maximum = "5", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Min(value = 0, message = "{validation.product.rating.min}") @Max(value = 5, message = "{validation.product.rating.max}") Double minRating
) {}
