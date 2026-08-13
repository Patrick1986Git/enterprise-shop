/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.company.shop.common.model.AuditableEntity;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Domain entity representing a user's shopping cart.
 * <p>
 * This entity acts as an aggregate root for cart operations, managing the
 * lifecycle of {@link CartItem}s. It ensures data consistency during product
 * addition, removal, and quantity updates.
 * </p>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "carts")
public class Cart extends AuditableEntity {

	/**
	 * The owner of the cart. Each user is restricted to a single unique cart.
	 */
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	/**
	 * List of items currently in the cart. Uses {@code orphanRemoval} to ensure
	 * items are deleted from DB when removed from the list.
	 */
	@OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<CartItem> items = new ArrayList<>();

	/**
	 * Default constructor required by JPA.
	 */
	protected Cart() {
	}

	/**
	 * Constructs a new cart for a specific user.
	 *
	 * @param user the cart owner.
	 */
	public Cart(User user) {
		this.user = user;
	}

	/**
	 * Adds a product to the cart or increases the quantity if it already exists.
	 *
	 * @param product  the product to be added.
	 * @param quantity the amount to be added.
	 */
	public void addItem(Product product, int quantity) {
		Optional<CartItem> existingItem = items.stream()
				.filter(item -> item.getProduct().getId().equals(product.getId())).findFirst();

		if (existingItem.isPresent()) {
			existingItem.get().increaseQuantity(quantity);
		} else {
			items.add(new CartItem(this, product, quantity));
		}
	}

	/**
	 * Removes all quantities of a specific product from the cart.
	 *
	 * @param productId unique identifier of the product to remove.
	 */
	public void removeItem(UUID productId) {
		items.removeIf(item -> item.getProduct().getId().equals(productId));
	}

	/**
	 * Updates the quantity of a specific product to an absolute value.
	 *
	 * @param productId unique identifier of the product.
	 * @param quantity  the new absolute quantity.
	 */
	public void updateItemQuantity(UUID productId, int quantity) {
		items.stream().filter(item -> item.getProduct().getId().equals(productId)).findFirst()
				.ifPresent(item -> item.updateQuantity(quantity));
	}

	/**
	 * Removes all items from the cart.
	 */
	public void clear() {
		items.clear();
	}

	public void reconcileItem(UUID productId, int checkedOutQuantity) {
		items.stream()
				.filter(item -> item.getProduct().getId().equals(productId))
				.findFirst()
				.ifPresent(item -> {
					if (item.getQuantity() <= checkedOutQuantity) {
						items.remove(item);
					} else {
						item.updateQuantity(item.getQuantity() - checkedOutQuantity);
					}
				});
	}

	/**
	 * Calculates the total monetary value of all items in the cart.
	 *
	 * @return a {@link BigDecimal} representing the total price.
	 */
	public BigDecimal calculateTotalAmount() {
		return items.stream().map(item -> item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public List<CartItem> getItems() {
		return items;
	}

	public void setItems(List<CartItem> items) {
		this.items = items;
	}
}
