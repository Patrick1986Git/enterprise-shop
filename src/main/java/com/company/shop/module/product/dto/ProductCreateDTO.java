package com.company.shop.module.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class ProductCreateDTO {

	@NotBlank(message = "{validation.product.name.required}")
	@Size(max = 255, message = "{validation.product.name.size}")
	private String name;

	@NotBlank(message = "{validation.product.sku.required}")
	@Size(max = 50, message = "{validation.product.sku.size}")
	private String sku;

	@Size(max = 5000, message = "{validation.product.description.size}")
	private String description;

	@NotNull(message = "{validation.product.price.required}")
	@DecimalMin(value = "0.01", message = "{validation.product.price.min}")
	private BigDecimal price;

	@PositiveOrZero(message = "{validation.product.stock.positiveOrZero}")
	private int stock;

	@NotNull(message = "{validation.product.category.required}")
	private UUID categoryId;

	private List<String> imageUrls;

	public ProductCreateDTO() {
	}

	public ProductCreateDTO(String name, String sku, String description, BigDecimal price, int stock, UUID categoryId,
			List<String> imageUrls) {
		this.name = name;
		this.sku = sku;
		this.description = description;
		this.price = price;
		this.stock = stock;
		this.categoryId = categoryId;
		this.imageUrls = imageUrls;
	}

	public String getName() {
		return name;
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

	public List<String> getImageUrls() {
		return imageUrls;
	}
}
