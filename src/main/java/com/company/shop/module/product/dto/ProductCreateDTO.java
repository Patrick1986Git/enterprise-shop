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

	@NotBlank(message = "Nazwa produktu nie może być pusta")
	@Size(max = 255, message = "Nazwa produktu nie może przekraczać 255 znaków")
	private String name;

	@NotBlank(message = "SKU nie może być puste")
	@Size(max = 50, message = "SKU nie może przekraczać 50 znaków")
	private String sku;

	@Size(max = 5000, message = "Opis produktu nie może przekraczać 5000 znaków")
	private String description;

	@NotNull(message = "Cena jest wymagana")
	@DecimalMin(value = "0.01", message = "Cena musi być większa niż 0")
	private BigDecimal price;

	@PositiveOrZero(message = "Stan magazynowy nie może być ujemny")
	private int stock;

	@NotNull(message = "Kategoria jest wymagana")
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
