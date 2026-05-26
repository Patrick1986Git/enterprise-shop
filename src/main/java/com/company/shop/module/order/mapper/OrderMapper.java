/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.mapper;

import java.math.BigDecimal;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderItemResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;

/**
 * Enterprise-grade mapper for order-related data transformations.
 * <p>
 * This component handles the complex mapping between {@link Order} aggregates 
 * and their respective Data Transfer Objects. It ensures financial precision 
 * during subtotal calculations and manages relationship flattening.
 * </p>
 *
 * @since 1.0.0
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    /**
     * Converts an {@link Order} entity to a basic summary DTO.
     * <p>
     * Note: {@code paymentInfo} is explicitly ignored to satisfy strict mapping 
     * policies and will be populated via external payment service integration.
     * </p>
     *
     * @param order the source order aggregate.
     * @return summary order response DTO.
     */
    @Mapping(target = "paymentInfo", ignore = true)
    OrderResponseDTO toDto(Order order);

    /**
     * Maps an {@link Order} entity to a detailed view including user context.
     *
     * @param order the source order aggregate.
     * @return detailed order response DTO.
     */
    @Mapping(target = "userEmail", source = "user.email")
    OrderDetailedResponseDTO toDetailedDto(Order order);

    /**
     * Transforms an {@link OrderItem} to its DTO representation with calculated subtotal.
     * <p>
     * Uses a custom expression to ensure line-item financial totals are computed
     * during the mapping phase.
     * </p>
     *
     * @param item the order line item.
     * @return mapped order item DTO.
     */
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "productName", source = "productName")
    @Mapping(target = "sku", source = "productSku")
    @Mapping(target = "subtotal", expression = "java(calculateSubtotal(item))")
    OrderItemResponseDTO toItemDto(OrderItem item);

    /**
     * Calculates the monetary subtotal for an order item.
     *
     * @param item the line item to calculate.
     * @return product price multiplied by quantity, or {@link BigDecimal#ZERO} if data is missing.
     */
    default BigDecimal calculateSubtotal(OrderItem item) {
        if (item == null || item.getPrice() == null) {
            return BigDecimal.ZERO;
        }
        return item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }
}