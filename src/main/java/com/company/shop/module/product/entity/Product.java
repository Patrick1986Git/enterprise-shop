/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.SQLRestriction;

import com.company.shop.common.model.SoftDeleteEntity;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.exception.ProductDataInvalidException;
import com.company.shop.module.product.exception.ProductInsufficientStockException;
import com.company.shop.module.product.exception.ProductReviewCountInvalidException;
import com.company.shop.module.product.exception.ProductStockInvalidException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "products")
@SQLRestriction("deleted = false")
public class Product extends SoftDeleteEntity {

	private static final double MIN_AVERAGE_RATING = 0.0;
	private static final double MAX_AVERAGE_RATING = 5.0;

	@Column(nullable = false, length = 255)
	private String name;

	@Column(nullable = false, unique = true, length = 255)
	private String slug;

	@Column(nullable = false, unique = true, length = 50)
	private String sku;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(nullable = false)
	private int stock;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id")
	private Category category;

	@Column(name = "average_rating", nullable = false, columnDefinition = "NUMERIC(3,2)")
	private Double averageRating = 0.0;

	@Column(name = "review_count", nullable = false)
	private int reviewCount = 0;

	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProductImage> images = new ArrayList<>();

	protected Product() {
	}

	public Product(String name, String slug, String sku, String description, BigDecimal price, int stock,
			Category category) {
		validateRequiredText(name, "Product name cannot be blank");
		validateRequiredText(slug, "Product slug cannot be blank");
		validateRequiredText(sku, "Product SKU cannot be blank");
		validatePrice(price);
		validateStock(stock);
		if (category == null) {
			throw new ProductDataInvalidException("Product category is required");
		}

		this.name = name;
		this.slug = slug;
		this.sku = sku;
		this.description = description;
		this.price = price;
		this.stock = stock;
		this.category = category;
		this.averageRating = 0.0;
		this.reviewCount = 0;
	}

	public void replaceImages(List<String> newImageUrls) {
		this.images.clear();
		if (newImageUrls != null) {
			newImageUrls.forEach(this::addImage);
		}
	}

	public void addImage(String url) {
		ProductImage image = new ProductImage(url, this);
		this.images.add(image);
	}

	public String getMainImageUrl() {
		if (images != null && !images.isEmpty()) {
			return images.get(0).getImageUrl();
		}
		return null;
	}

	public void updateRatings(Double newAverage, int newCount) {
		if (newCount < 0) {
			throw new ProductReviewCountInvalidException(newCount);
		}

		double safeAverage = newAverage != null ? newAverage : 0.0;
		if (newCount == 0) {
			safeAverage = 0.0;
		}
		safeAverage = Math.max(MIN_AVERAGE_RATING, Math.min(MAX_AVERAGE_RATING, safeAverage));

		this.averageRating = BigDecimal.valueOf(safeAverage)
				.setScale(2, RoundingMode.HALF_UP)
				.doubleValue();
		this.reviewCount = newCount;
	}

	public void update(String name, String slug, String sku, String description, BigDecimal price, int stock,
			Category category) {
		validateRequiredText(name, "Product name cannot be blank");
		validateRequiredText(slug, "Product slug cannot be blank");
		validateRequiredText(sku, "Product SKU cannot be blank");
		validatePrice(price);
		validateStock(stock);
		if (category == null) {
			throw new ProductDataInvalidException("Product category is required");
		}

		this.name = name;
		this.slug = slug;
		this.sku = sku;
		this.description = description;
		this.price = price;
		this.stock = stock;
		this.category = category;
	}

	public void updateStock(int newStock) {
		validateStock(newStock);
		this.stock = newStock;
	}

	public void decreaseStock(int quantityToDecrease) {
		if (quantityToDecrease <= 0) {
			throw new ProductStockInvalidException("decrease", quantityToDecrease);
		}
		if (this.stock < quantityToDecrease) {
			throw new ProductInsufficientStockException(this.name, quantityToDecrease, this.stock);
		}
		this.stock -= quantityToDecrease;
	}

	public void restoreReservedStock(int quantityToRestore) {
		if (quantityToRestore <= 0) {
			throw new ProductStockInvalidException("restore", quantityToRestore);
		}
		try {
			this.stock = Math.addExact(this.stock, quantityToRestore);
		} catch (ArithmeticException ex) {
			throw new ProductStockInvalidException("Restored product stock exceeds the supported range");
		}
	}

	private void validateRequiredText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new ProductDataInvalidException(message);
		}
	}

	private void validatePrice(BigDecimal value) {
		if (value == null || value.signum() <= 0) {
			throw new ProductDataInvalidException("Product price must be greater than zero");
		}
	}

	private void validateStock(int value) {
		if (value < 0) {
			throw new ProductStockInvalidException(value);
		}
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

	public Category getCategory() {
		return category;
	}

	public Double getAverageRating() {
		return averageRating;
	}

	public int getReviewCount() {
		return reviewCount;
	}

	public List<ProductImage> getImages() {
		return images == null ? List.of() : List.copyOf(images);
	}
}
