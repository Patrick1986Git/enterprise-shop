package com.company.shop.module.cart.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing cart items and computed totals.")
public class CartDTO {

	@Schema(description = "Items currently in the cart.", accessMode = Schema.AccessMode.READ_ONLY)
	private final List<CartItemDTO> items;

	public CartDTO(List<CartItemDTO> items) {
		this.items = items != null ? items : Collections.emptyList();
	}

	public List<CartItemDTO> getItems() {
		return items;
	}
	@Schema(description = "Total monetary amount for all cart items.", example = "159.98", accessMode = Schema.AccessMode.READ_ONLY)
	public BigDecimal getTotalAmount() {
		return items.stream().map(CartItemDTO::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
	@Schema(description = "Total number of product units in the cart.", example = "2", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
	public int getTotalItems() {
		return items.stream().mapToInt(CartItemDTO::getQuantity).sum();
	}
}
