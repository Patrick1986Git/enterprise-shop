/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Data Transfer Object for detailed product information.
 * <p>
 * This class provides a read-only snapshot of a product's state, enriched with
 * category details and media assets. It is designed to be immutable to ensure
 * thread-safety and data integrity across application layers.
 * </p>
 *
 * @since 1.0.0
 */
/**
 * Data Transfer Object for detailed product information.
 * <p>
 * This class provides a read-only snapshot of a product's state, enriched with
 * category details and media assets. It is designed to be immutable to ensure
 * thread-safety and data integrity across application layers.
 * </p>
 *
 * @since 1.0.0
 */
@Schema(description = "Response payload containing product details.")
public class ProductResponseDTO {

    @Schema(description = "Unique product identifier.", example = "11111111-1111-1111-1111-111111111111", accessMode = Schema.AccessMode.READ_ONLY)
    private final UUID id;
    @Schema(description = "Product display name.", example = "Wireless Keyboard", accessMode = Schema.AccessMode.READ_ONLY)
    private final String name;
    @Schema(description = "URL-friendly product identifier.", example = "wireless-keyboard", accessMode = Schema.AccessMode.READ_ONLY)
    private final String slug;
    @Schema(description = "Product stock keeping unit.", example = "KEYBOARD-001", accessMode = Schema.AccessMode.READ_ONLY)
    private final String sku;
    @Schema(description = "Product description.", example = "Compact wireless keyboard with rechargeable battery.", accessMode = Schema.AccessMode.READ_ONLY)
    private final String description;
    @Schema(description = "Current unit price.", example = "79.99", accessMode = Schema.AccessMode.READ_ONLY)
    private final BigDecimal price;
    @Schema(description = "Available stock quantity.", example = "25", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
    private final int stock;
    @Schema(description = "Assigned category identifier.", example = "22222222-2222-2222-2222-222222222222", accessMode = Schema.AccessMode.READ_ONLY)
    private final UUID categoryId;
    @Schema(description = "Assigned category display name.", example = "Electronics", accessMode = Schema.AccessMode.READ_ONLY)
    private final String categoryName;
    @Schema(description = "Average customer rating.", example = "4.5", minimum = "0", maximum = "5", accessMode = Schema.AccessMode.READ_ONLY)
    private final Double averageRating;
    @Schema(description = "Number of submitted reviews.", example = "12", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
    private final int reviewCount;

    /**
     * List of associated image URLs, typically ordered by display priority.
     */
    @Schema(description = "Product image URLs ordered for display.", example = "[\"https://cdn.example.com/products/keyboard-001.jpg\"]", accessMode = Schema.AccessMode.READ_ONLY)
    private final List<String> imageUrls;

    /**
     * Full constructor for initializing an immutable product response.
     *
     * @param id            unique product identifier.
     * @param name          display name.
     * @param slug          SEO-friendly URL identifier.
     * @param sku           stock keeping unit.
     * @param description   marketing content.
     * @param price         current unit price.
     * @param stock         available quantity.
     * @param categoryId    parent category ID.
     * @param categoryName  parent category display name (flattened).
     * @param averageRating computed user rating (defaults to 0.0 if null).
     * @param reviewCount   total number of submitted reviews.
     * @param imageUrls     collection of image resource locations.
     */
    public ProductResponseDTO(UUID id, String name, String slug, String sku, String description, BigDecimal price,
            int stock, UUID categoryId, String categoryName, Double averageRating, int reviewCount,
            List<String> imageUrls) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.sku = sku;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.averageRating = averageRating != null ? averageRating : 0.0;
        this.reviewCount = reviewCount;
        this.imageUrls = imageUrls;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getSku() {
        return sku;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public Double getAverageRating() {
        return averageRating;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    /**
     * Retrieves the list of URLs for product images.
     *
     * @return an unmodifiable list of image URLs.
     */
    public List<String> getImageUrls() {
        return imageUrls;
    }
}
