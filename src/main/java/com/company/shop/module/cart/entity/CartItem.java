/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.entity;

import java.util.UUID;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import com.company.shop.common.model.AuditableEntity;
import com.company.shop.module.product.entity.Product;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Entity representing a specific product line item within a {@link Cart}.
 * <p>
 * This class maintains the relationship between a shopping cart and the selected product,
 * including the desired quantity. Lifecycle is typically managed by the {@code Cart} aggregate root.
 * </p>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "cart_items")
public class CartItem extends AuditableEntity {

    /**
     * Parent cart this item belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /**
     * The product selected for purchase.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Product product;

    @Column(name = "product_id", insertable = false, updatable = false, nullable = false)
    private UUID productId;

    /**
     * Quantity of the specified product. Must be a positive integer.
     */
    private int quantity;

    /**
     * Default constructor required by JPA.
     */
    protected CartItem() {
    }

    /**
     * Constructs a new cart item with the specified product and quantity.
     *
     * @param cart     the parent shopping cart.
     * @param product  the product to be added.
     * @param quantity initial quantity of the product.
     */
    public CartItem(Cart cart, Product product, int quantity) {
        this.cart = cart;
        this.product = product;
        this.quantity = quantity;
    }

    /**
     * Updates the quantity to a new absolute value.
     *
     * @param newQuantity the new quantity to set.
     * @throws IllegalArgumentException if the quantity is less than or equal to zero.
     */
    public void updateQuantity(int newQuantity) {
        if (newQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        this.quantity = newQuantity;
    }

    /**
     * Increases the current quantity by a specified amount.
     *
     * @param amount the value to be added to the current quantity.
     */
    public void increaseQuantity(int amount) {
        this.quantity += amount;
    }

    // Getters and Setters

    public Cart getCart() {
        return cart;
    }

    public void setCart(Cart cart) {
        this.cart = cart;
    }

    public Product getProduct() {
        return product;
    }

    public UUID getProductId() {
        return product != null ? product.getId() : productId;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
