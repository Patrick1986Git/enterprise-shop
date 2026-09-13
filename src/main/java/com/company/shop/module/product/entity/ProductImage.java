/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.entity;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Entity representing a media asset associated with a {@link Product}.
 * <p>
 * This class stores the location of product images (typically hosted on a CDN or S3)
 * and maintains their display sequence via the {@code sortOrder} property.
 * </p>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    /**
     * Unique identifier for the product image.
     */
    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Absolute or relative URL to the image resource.
     * Validated for length to support long Cloudfront/S3 signed URLs.
     */
    @Column(name = "image_url", nullable = false, length = 512)
    private String imageUrl;

    /**
     * Determines the display sequence in the product gallery.
     * Lower values are typically displayed first.
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * The parent product that owns this image.
     * Uses {@link FetchType#LAZY} to optimize memory footprint during batch processing.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    /**
     * Default constructor required by JPA.
     */
    protected ProductImage() {
    }

    /**
     * Initializes a standalone product image at the first display position.
     *
     * @param imageUrl the resource location.
     * @param product  the associated product aggregate.
     */
    public ProductImage(String imageUrl, Product product) {
        this(imageUrl, product, 0);
    }

    ProductImage(String imageUrl, Product product, int sortOrder) {
        this.imageUrl = imageUrl;
        this.product = product;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Product getProduct() {
        return product;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    /**
     * Evaluates equality based on business identity (URL and Product association).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ProductImage that = (ProductImage) o;
        return Objects.equals(imageUrl, that.imageUrl) && 
               Objects.equals(product, that.product);
    }

    /**
     * Generates a hash code consistent with the equality logic.
     */
    @Override
    public int hashCode() {
        return Objects.hash(imageUrl, product);
    }
}
