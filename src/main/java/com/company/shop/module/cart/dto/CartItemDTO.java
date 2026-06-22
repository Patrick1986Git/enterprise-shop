package com.company.shop.module.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing basic cart item details.")
public class CartItemDTO {

	@Schema(description = "Product identifier.", example = "11111111-1111-1111-1111-111111111111", accessMode = Schema.AccessMode.READ_ONLY)
	private final UUID productId;
	@Schema(description = "Product stock keeping unit.", example = "KEYBOARD-001", accessMode = Schema.AccessMode.READ_ONLY)
	private final String sku;
	@Schema(description = "Product display name.", example = "Wireless Keyboard", accessMode = Schema.AccessMode.READ_ONLY)
	private final String productName;
	@Schema(description = "Unit price.", example = "79.99", accessMode = Schema.AccessMode.READ_ONLY)
	private final BigDecimal price;
	@Schema(description = "Quantity in the cart.", example = "2", minimum = "1", accessMode = Schema.AccessMode.READ_ONLY)
	private final int quantity;

	public CartItemDTO(UUID productId, String sku, String productName, BigDecimal price, int quantity) {
		this.productId = productId;
		this.sku = sku;
		this.productName = productName;
		this.price = price;
		this.quantity = quantity;
	}

	public UUID getProductId() {
		return productId;
	}

	public String getSku() {
		return sku;
	}

	public String getProductName() {
		return productName;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public int getQuantity() {
		return quantity;
	}
	@Schema(description = "Line total calculated from unit price and quantity.", example = "159.98", accessMode = Schema.AccessMode.READ_ONLY)
	public BigDecimal getTotal() {
		return price.multiply(BigDecimal.valueOf(quantity));
	}
}
