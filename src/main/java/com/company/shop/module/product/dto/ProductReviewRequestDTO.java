package com.company.shop.module.product.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductReviewRequestDTO(
    @NotNull(message = "{validation.review.product.required}") UUID productId,
    @Min(value = 1, message = "{validation.review.rating.min}") @Max(value = 5, message = "{validation.review.rating.max}") int rating,
    @Size(max = 1000, message = "{validation.review.comment.size}") String comment
) {}