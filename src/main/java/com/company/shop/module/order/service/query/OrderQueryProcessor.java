/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service.query;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.exception.OrderAccessDeniedException;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.mapper.OrderMapper;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.security.SecurityConstants;

@Component
public class OrderQueryProcessor {

    private final OrderRepository orderRepo;
    private final CurrentUserFacade currentUserFacade;
    private final OrderMapper mapper;

    public OrderQueryProcessor(OrderRepository orderRepo,
            CurrentUserFacade currentUserFacade,
            OrderMapper mapper) {
        this.orderRepo = orderRepo;
        this.currentUserFacade = currentUserFacade;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public OrderDetailedResponseDTO findById(UUID id) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        CurrentUserSnapshot currentUser = currentUserFacade.getCurrentUser();
        boolean isAdmin = currentUser.hasRole(SecurityConstants.ROLE_ADMIN);

        if (!isAdmin && !order.getUserId().equals(currentUser.id())) {
            throw new OrderAccessDeniedException();
        }
        return mapper.toDetailedDto(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDTO> findAll(Pageable pageable) {
        return orderRepo.findAll(pageable).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDTO> findMyOrders(Pageable pageable) {
        CurrentUserSnapshot currentUser = currentUserFacade.getCurrentUser();
        return orderRepo.findByUserId(currentUser.id(), pageable).map(mapper::toDto);
    }
}
