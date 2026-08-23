package com.company.shop.module.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for creating a product.")
public class ProductCreateDTO {

	@Schema(
	        description = "Product display name.",
	        example = "Wireless Keyboard",
	        maxLength = 255,
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotBlank(message = "{validation.product.name.required}")
	@Size(max = 255, message = "{validation.product.name.size}")
	private String name;

	@Schema(
	        description = "Unique stock keeping unit.",
	        example = "KEYBOARD-001",
	        maxLength = 50,
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotBlank(message = "{validation.product.sku.required}")
	@Size(max = 50, message = "{validation.product.sku.size}")
	private String sku;

	@Schema(
	        description = "Product description.",
	        example = "Compact wireless keyboard with rechargeable battery.",
	        maxLength = 5000,
	        requiredMode = Schema.RequiredMode.NOT_REQUIRED
	)
	@Size(max = 5000, message = "{validation.product.description.size}")
	private String description;

	@Schema(
	        description = "Unit price of the product.",
	        example = "79.99",
	        minimum = "0.01",
	        maximum = "9999999999.99",
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotNull(message = "{validation.product.price.required}")
	@DecimalMin(value = "0.01", message = "{validation.product.price.min}")
	@Digits(integer = 10, fraction = 2, message = "{validation.product.price.digits}")
	private BigDecimal price;

	@Schema(
	        description = "Available stock quantity.",
	        example = "25",
	        minimum = "0",
	        requiredMode = Schema.RequiredMode.NOT_REQUIRED
	)
	@PositiveOrZero(message = "{validation.product.stock.positiveOrZero}")
	private int stock;

	@Schema(
	        description = "Identifier of the category assigned to the product.",
	        example = "11111111-1111-1111-1111-111111111111",
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotNull(message = "{validation.product.category.required}")
	private UUID categoryId;

	@Schema(
	        description = "Product image URLs ordered for display.",
	        example = "[\"https://cdn.example.com/products/keyboard-001.jpg\"]",
	        requiredMode = Schema.RequiredMode.NOT_REQUIRED
	)
	private List<String> imageUrls;

	public ProductCreateDTO() {
	}

	public ProductCreateDTO(
			String name,
			String sku,
			String description,
			BigDecimal price,
			int stock,
			UUID categoryId,
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
