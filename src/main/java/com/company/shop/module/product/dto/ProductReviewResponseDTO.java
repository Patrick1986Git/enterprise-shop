package com.company.shop.module.product.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing product review details.")
public record ProductReviewResponseDTO(
    @Schema(
            description = "Unique review identifier.",
            example = "22222222-2222-2222-2222-222222222222",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    UUID id,
    @Schema(
            description = "Display name of the review author.",
            example = "Alex Morgan",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    String authorName,
    @Schema(
            description = "Customer rating from 1 to 5.",
            example = "5",
            minimum = "1",
            maximum = "5",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    int rating,
    @Schema(
            description = "Review comment provided by the customer.",
            example = "Excellent quality and fast delivery.",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    String comment,
    @Schema(
            description = "Date and time when the review was created.",
            example = "2026-06-22T10:15:30",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    LocalDateTime createdAt
) {}
