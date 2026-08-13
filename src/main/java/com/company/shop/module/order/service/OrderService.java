/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;

/**
 * Core service interface for managing order processing and lifecycle.
 * <p>
 * This service coordinates the transition from a shopping cart to a finalized order,
 * handles administrative lookups, and provides customer-specific order history.
 * Implementation is expected to handle transaction boundaries and stock validation.
 * </p>
 *
 * @since 1.0.0
 */
public interface OrderService {

    /**
     * Converts the current user's shopping cart into a permanent order record.
     * <p>
     * This operation involves inventory deduction, price locking, and optional
     * discount application. Upon success, the source cart is typically cleared.
     * </p>
     *
     * @param request DTO containing checkout parameters (e.g., discount codes).
     * @return a summary of the newly created order.
     * @throws com.company.shop.common.exception.BusinessException when checkout constraints are violated.
     */
    OrderResponseDTO placeOrderFromCart(String idempotencyKey, OrderCheckoutRequestDTO request);

    /**
     * Retrieves full details of a specific order.
     *
     * @param id the unique identifier of the order.
     * @return a detailed DTO including line items and customer information.
     * @throws com.company.shop.module.order.exception.OrderNotFoundException if the order does not exist.
     */
    OrderDetailedResponseDTO findById(UUID id);

    /**
     * Retrieves a paginated overview of all orders within the system.
     * <p>
     * Primarily intended for administrative dashboards to monitor global sales 
     * and fulfillment status.
     * </p>
     *
     * @param pageable pagination and sorting configuration.
     * @return a page of order summaries.
     */
    Page<OrderResponseDTO> findAll(Pageable pageable);

    /**
     * Retrieves a paginated history of orders belonging to the currently authenticated user.
     *
     * @param pageable pagination and sorting configuration.
     * @return a page of the user's past orders.
     */
    Page<OrderResponseDTO> findMyOrders(Pageable pageable);
}
