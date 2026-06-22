package com.company.shop.module.product.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for submitting a product review.")
public record ProductReviewRequestDTO(
    @Schema(
            description = "Identifier of the reviewed product.",
            example = "11111111-1111-1111-1111-111111111111",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "{validation.review.product.required}")
    UUID productId,
    @Schema(
            description = "Customer rating from 1 to 5.",
            example = "5",
            minimum = "1",
            maximum = "5",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @Min(value = 1, message = "{validation.review.rating.min}")
    @Max(value = 5, message = "{validation.review.rating.max}")
    int rating,
    @Schema(
            description = "Optional review comment.",
            example = "Excellent quality and fast delivery.",
            maxLength = 1000,
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    @Size(max = 1000, message = "{validation.review.comment.size}")
    String comment
) {}
