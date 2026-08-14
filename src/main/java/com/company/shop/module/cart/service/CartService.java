/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.service;

import java.util.List;
import java.util.UUID;

import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.dto.UpdateCartItemRequestDTO;
import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.cart.entity.Cart;

/**
 * Service interface for managing shopping cart lifecycle and business operations.
 * <p>
 * This service acts as the primary entry point for cart-related logic, providing
 * both DTO-based responses for the Web API and direct entity access for 
 * inter-module communication (e.g., during the Checkout process).
 * </p>
 *
 * @since 1.0.0
 */
public interface CartService {

    /**
     * Retrieves the shopping cart DTO for the currently authenticated user.
     *
     * @return a {@link CartResponseDTO} enriched with calculated totals and product details.
     */
    CartResponseDTO getMyCart();

    /**
     * Adds a product to the user's cart with stock availability validation.
     *
     * @param request DTO containing product identifier and desired quantity.
     * @return the updated {@link CartResponseDTO}.
     */
    CartResponseDTO addToCart(AddToCartRequestDTO request);

    /**
     * Updates the quantity of an existing line item in the cart.
     *
     * @param productId unique identifier of the product to update.
     * @param request   DTO containing the new absolute quantity.
     * @return the updated {@link CartResponseDTO}.
     */
    CartResponseDTO updateItemQuantity(UUID productId, UpdateCartItemRequestDTO request);

    /**
     * Completely removes a product from the user's shopping cart.
     *
     * @param productId unique identifier of the product to be removed.
     * @return the updated {@link CartResponseDTO} after removal.
     */
    CartResponseDTO removeItem(UUID productId);

    /**
     * Purges all items from the current user's cart.
     * <p>
     * Typically invoked after a successful order placement or manual cart reset.
     * </p>
     */
    void clearCart();

    /**
     * Purges all items from a specific user cart (system/internal use).
     *
     * @param userId unique identifier of cart owner.
     */
    void clearCartForUser(UUID userId);

    void reconcileCartForUser(UUID userId, List<CartCheckoutItem> checkedOutItems);

    /**
     * Retrieves the raw {@link Cart} entity associated with a specific user.
     * <p>
     * <strong>Note:</strong> This method is intended for internal use by other modules 
     * (e.g., Order Module) to process the cart within a single transaction. 
     * It should not be exposed directly to the REST controller.
     * </p>
     *
     * @param userId unique identifier of the user.
     * @return the {@link Cart} entity, either existing or newly initialized.
     */
    Cart getCartEntityForUser(UUID userId);
}
