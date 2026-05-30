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
import org.springframework.stereotype.Service;

import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.service.checkout.OrderCheckoutProcessor;
import com.company.shop.module.order.service.query.OrderQueryProcessor;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderCheckoutProcessor checkoutProcessor;
    private final OrderQueryProcessor queryProcessor;

    public OrderServiceImpl(OrderCheckoutProcessor checkoutProcessor,
            OrderQueryProcessor queryProcessor) {
        this.checkoutProcessor = checkoutProcessor;
        this.queryProcessor = queryProcessor;
    }

    @Override
    public OrderResponseDTO placeOrderFromCart(OrderCheckoutRequestDTO request) {
        return checkoutProcessor.placeOrderFromCart(request);
    }

    @Override
    public OrderDetailedResponseDTO findById(UUID id) {
        return queryProcessor.findById(id);
    }

    @Override
    public Page<OrderResponseDTO> findAll(Pageable pageable) {
        return queryProcessor.findAll(pageable);
    }

    @Override
    public Page<OrderResponseDTO> findMyOrders(Pageable pageable) {
        return queryProcessor.findMyOrders(pageable);
    }
}
