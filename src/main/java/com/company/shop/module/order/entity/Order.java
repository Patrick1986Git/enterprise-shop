/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.SQLRestriction;

import com.company.shop.common.model.SoftDeleteEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * Aggregate root representing a customer order within the system.
 * <p>
 * This entity manages the lifecycle of an order, including status transitions,
 * total amount recalculations, and discount application. It ensures that 
 * financial invariants are maintained throughout the order's existence.
 * </p>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "orders")
@SQLRestriction("deleted = false")
public class Order extends SoftDeleteEntity {

    /**
     * Identifier of the customer who placed the order.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Email snapshot of the customer who placed the order.
     */
    @Column(name = "user_email", nullable = false)
    private String userEmail;

    /**
     * Current business status of the order.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OrderStatus status;

    /**
     * Final gross amount of the order after all calculations and discounts.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * Collection of items included in this order. 
     * Managed via cascade operations to ensure atomicity.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Default constructor required by JPA.
     */
    protected Order() {
    }

    /**
     * Initializes a new order for a specific user with a default {@link OrderStatus#NEW} status.
     *
     * @param userId the identifier of the user who placed the order.
     * @param userEmail the email snapshot of the user who placed the order.
     */
    public Order(UUID userId, String userEmail) {
        this.userId = userId;
        this.userEmail = userEmail;
        this.status = OrderStatus.NEW;
        this.totalAmount = BigDecimal.ZERO;
    }

    /**
     * Adds an item to the order and automatically triggers total amount recalculation.
     * <p>
     * This method maintains the bidirectional relationship between {@code Order} 
     * and {@code OrderItem}.
     * </p>
     *
     * @param item the order line item to be added.
     */
    public void addItem(OrderItem item) {
        if (item == null) {
            return;
        }
        this.items.add(item);
        item.setOrder(this);
        recalculateTotal();
    }

    /**
     * Applies a discount code to the total order amount.
     * <p>
     * Note: This operation modifies the current {@code totalAmount}. In high-audit 
     * environments, consider storing the original amount and discount value separately.
     * </p>
     *
     * @param discountCode the discount code to be applied.
     * @throws IllegalStateException if the discount code is expired or usage limits are exceeded.
     */
    public void applyDiscount(DiscountCode discountCode) {
        if (discountCode == null) {
            return;
        }

        if (!discountCode.canBeUsed()) {
            throw new IllegalStateException("Discount code cannot be used (expired or limit reached)");
        }

        BigDecimal multiplier = BigDecimal.valueOf(100 - discountCode.getDiscountPercent())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        this.totalAmount = this.totalAmount.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        discountCode.incrementUsage();
    }

    /**
     * Transitions the order status to PAID.
     *
     * @throws IllegalStateException if the current status is not NEW.
     */
    public void markAsPaid() {
        if (this.status != OrderStatus.NEW) {
            throw new IllegalStateException("Only NEW orders can be marked as PAID");
        }
        this.status = OrderStatus.PAID;
    }

    /**
     * Internally recalculates the sum of all item prices multiplied by their quantities.
     */
    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    /**
     * Returns an unmodifiable view of the order items.
     * <p>
     * Direct manipulation of the list is prohibited to ensure that 
     * {@link #recalculateTotal()} is always invoked via {@link #addItem(OrderItem)}.
     * </p>
     *
     * @return a read-only list of {@link OrderItem}s.
     */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}