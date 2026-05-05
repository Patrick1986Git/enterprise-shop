/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.entity.CartItem;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.DiscountCode;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.exception.DiscountCodeInvalidException;
import com.company.shop.module.order.exception.EmptyCartCheckoutException;
import com.company.shop.module.order.exception.OrderAccessDeniedException;
import com.company.shop.module.order.exception.OrderInsufficientStockException;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.mapper.OrderMapper;
import com.company.shop.module.order.repository.DiscountCodeRepository;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.SecurityConstants;

import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final PaymentRepository paymentRepo;
    private final DiscountCodeRepository discountCodeRepo;
    private final UserService userService;
    private final CartService cartService;
    private final OrderMapper mapper;
    private final PaymentService paymentService;

    public OrderServiceImpl(OrderRepository orderRepo,
            ProductRepository productRepo,
            PaymentRepository paymentRepo,
            DiscountCodeRepository discountCodeRepo,
            UserService userService,
            CartService cartService,
            OrderMapper mapper,
            PaymentService paymentService) {
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.paymentRepo = paymentRepo;
        this.discountCodeRepo = discountCodeRepo;
        this.userService = userService;
        this.cartService = cartService;
        this.mapper = mapper;
        this.paymentService = paymentService;
    }

    @Override
    @Transactional
    public OrderResponseDTO placeOrderFromCart(OrderCheckoutRequestDTO request) {
        Order savedOrder = createPendingOrder(request);
        log.info("Order created during checkout orderId={} userId={} status={} totalAmount={} itemsCount={}",
                savedOrder.getId(), savedOrder.getUser().getId(), savedOrder.getStatus(), savedOrder.getTotalAmount(),
                savedOrder.getItems().size());
        PaymentIntentResponseDTO stripeInfo = paymentService.createPaymentIntent(savedOrder);

        OrderResponseDTO baseDto = mapper.toDto(savedOrder);
        return new OrderResponseDTO(
                baseDto.id(),
                baseDto.status(),
                baseDto.totalAmount(),
                baseDto.createdAt(),
                stripeInfo);
    }

    private Order createPendingOrder(OrderCheckoutRequestDTO request) {
        User user = userService.getCurrentUserEntity();
        log.info("Checkout started for userId={}", user.getId());
        Cart cart = cartService.getCartEntityForUser(user.getId());

        if (cart.getItems().isEmpty()) {
            throw new EmptyCartCheckoutException();
        }

        Order order = new Order(user);

        for (CartItem cartItem : cart.getItems()) {
            Product product = productRepo.findByIdWithLock(cartItem.getProduct().getId())
                    .orElseThrow(() -> new ProductNotFoundException(cartItem.getProduct().getId()));

            if (product.getStock() < cartItem.getQuantity()) {
                throw new OrderInsufficientStockException(product.getId(), cartItem.getQuantity(), product.getStock());
            }

            product.decreaseStock(cartItem.getQuantity());
            order.addItem(new OrderItem(product, cartItem.getQuantity(), product.getPrice()));
        }

        if (request.discountCode() != null && !request.discountCode().isBlank()) {
            String normalizedDiscountCode = request.discountCode().trim();
            DiscountCode dc = discountCodeRepo.findByCodeIgnoreCase(normalizedDiscountCode)
                    .orElseThrow(() -> new DiscountCodeInvalidException(normalizedDiscountCode));

            if (!dc.canBeUsed()) {
                throw new DiscountCodeInvalidException(normalizedDiscountCode);
            }

            order.applyDiscount(dc);
        }

        Order savedOrder = orderRepo.save(order);
        paymentRepo.save(new Payment(savedOrder, "STRIPE", savedOrder.getTotalAmount()));

        return savedOrder;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailedResponseDTO findById(UUID id) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        User currentUser = userService.getCurrentUserEntity();
        boolean isAdmin = currentUser.getRoles().stream()
                .anyMatch(r -> r.getName().equals(SecurityConstants.ROLE_ADMIN));

        if (!isAdmin && !order.getUser().getId().equals(currentUser.getId())) {
            throw new OrderAccessDeniedException();
        }
        return mapper.toDetailedDto(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponseDTO> findAll(Pageable pageable) {
        return orderRepo.findAll(pageable).map(mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponseDTO> findMyOrders(Pageable pageable) {
        User currentUser = userService.getCurrentUserEntity();
        return orderRepo.findByUser(currentUser, pageable).map(mapper::toDto);
    }
}
