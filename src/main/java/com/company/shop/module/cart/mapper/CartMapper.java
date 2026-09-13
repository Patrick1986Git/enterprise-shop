/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.mapper;

import java.math.BigDecimal;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import com.company.shop.module.cart.dto.CartItemResponseDTO;
import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.entity.CartItem;

/**
 * Enterprise-grade mapper for shopping cart transformations.
 * <p>
 * This component handles the complex conversion between domain entities ({@link Cart}, {@link CartItem})
 * and their corresponding Data Transfer Objects. It includes real-time business calculations
 * and stock availability indicators.
 * </p>
 *
 * @since 1.0.0
 */
@Mapper(componentModel = "spring")
public interface CartMapper {

    /**
     * Threshold defining the "Low Stock" status for product availability.
     */
    int LOW_STOCK_THRESHOLD = 5;

    /**
     * Maps a {@link Cart} entity to a {@link CartResponseDTO}.
     * <p>
     * Calculated fields like {@code totalAmount} and {@code totalItemsCount} are 
     * derived using expression-based mapping.
     * </p>
     *
     * @param cart the source cart entity.
     * @return a comprehensive cart response DTO.
     */
    @Mapping(target = "items", source = "items")
    @Mapping(target = "totalAmount", expression = "java(cart.calculateTotalAmount())")
    @Mapping(target = "totalItemsCount", expression = "java(cart.getItems().stream().mapToInt(com.company.shop.module.cart.entity.CartItem::getQuantity).sum())")
    CartResponseDTO toDTO(Cart cart);

    /**
     * Maps a {@link CartItem} entity to a {@link CartItemResponseDTO}.
     * <p>
     * Note: {@code mainImageUrl} is explicitly ignored here as it usually requires 
     * specific resolution from an external media service or CDN provider.
     * </p>
     *
     * @param item the source cart item entity.
     * @return a flattened cart item response DTO.
     */
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "productName", source = "product.name")
    @Mapping(target = "productSlug", source = "product.slug")
    @Mapping(target = "unitPrice", source = "product.price")
    @Mapping(target = "stockAvailable", source = "product.stock")
    @Mapping(target = "subtotal", expression = "java(calculateSubtotal(item))")
    @Mapping(target = "isLowStock", source = "item", qualifiedByName = "checkLowStock")
    @Mapping(target = "available", expression = "java(item.getProduct() != null)")
    @Mapping(target = "mainImageUrl", ignore = true) 
    CartItemResponseDTO toItemDTO(CartItem item);

    /**
     * Calculates the subtotal for a single cart line item.
     *
     * @param item the cart item source.
     * @return the product price multiplied by quantity, or ZERO if data is incomplete.
     */
    @Named("calculateSubtotal")
    default BigDecimal calculateSubtotal(CartItem item) {
        if (item == null || item.getProduct() == null)
            return BigDecimal.ZERO;
        return item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }

    /**
     * Evaluates if the current product stock is below the enterprise threshold.
     *
     * @param item the cart item source.
     * @return true if stock level is critical.
     */
    @Named("checkLowStock")
    default boolean checkLowStock(CartItem item) {
        if (item == null || item.getProduct() == null)
            return false;
        return item.getProduct().getStock() < LOW_STOCK_THRESHOLD;
    }
}
