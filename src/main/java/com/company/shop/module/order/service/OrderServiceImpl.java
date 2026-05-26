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
import io.micrometer.core.instrument.MeterRegistry;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.cart.api.internal.CartCheckoutSnapshot;
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
import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.SecurityConstants;

import org.springframework.transaction.annotation.Transactional;


@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);
    private static final String CHECKOUT_METRIC = "shop.checkout.total";
    private static final String RESULT_TAG = "result";

    private final OrderRepository orderRepo;
    private final ProductCatalogFacade productCatalogFacade;
    private final PaymentRepository paymentRepo;
    private final DiscountCodeRepository discountCodeRepo;
    private final UserService userService;
    private final CartCheckoutFacade cartCheckoutFacade;
    private final OrderMapper mapper;
    private final PaymentService paymentService;
    private final MeterRegistry meterRegistry;
    public OrderServiceImpl(OrderRepository orderRepo,
            ProductCatalogFacade productCatalogFacade,
            PaymentRepository paymentRepo,
            DiscountCodeRepository discountCodeRepo,
            UserService userService,
            CartCheckoutFacade cartCheckoutFacade,
            OrderMapper mapper,
            PaymentService paymentService,
            MeterRegistry meterRegistry) {
        this.orderRepo = orderRepo;
        this.productCatalogFacade = productCatalogFacade;
        this.paymentRepo = paymentRepo;
        this.discountCodeRepo = discountCodeRepo;
        this.userService = userService;
        this.cartCheckoutFacade = cartCheckoutFacade;
        this.mapper = mapper;
        this.paymentService = paymentService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public OrderResponseDTO placeOrderFromCart(OrderCheckoutRequestDTO request) {
        incrementCheckoutMetric("attempt");
        try {
            Order savedOrder = createPendingOrder(request);
            log.info("Order created during checkout orderId={} userId={} status={} totalAmount={} itemsCount={}",
                    savedOrder.getId(), savedOrder.getUser().getId(), savedOrder.getStatus(), savedOrder.getTotalAmount(),
                    savedOrder.getItems().size());
            PaymentIntentResponseDTO stripeInfo = paymentService.createPaymentIntent(savedOrder);

            OrderResponseDTO baseDto = mapper.toDto(savedOrder);
            incrementCheckoutMetric("success");
            return new OrderResponseDTO(
                    baseDto.id(),
                    baseDto.status(),
                    baseDto.totalAmount(),
                    baseDto.createdAt(),
                    stripeInfo);
        } catch (Exception ex) {
            incrementCheckoutMetric("failure");
            throw ex;
        }
    }

    private void incrementCheckoutMetric(String result) {
        meterRegistry.counter(CHECKOUT_METRIC, RESULT_TAG, result).increment();
    }

    private Order createPendingOrder(OrderCheckoutRequestDTO request) {
        User user = userService.getCurrentUserEntity();
        log.info("Checkout started for userId={}", user.getId());
        CartCheckoutSnapshot cart = cartCheckoutFacade.getCartForCheckout(user.getId());

        if (cart.isEmpty()) {
            throw new EmptyCartCheckoutException();
        }

        Order order = new Order(user);

        for (CartCheckoutItem cartItem : cart.items()) {
            try {
                CheckoutProduct product = productCatalogFacade.reserveProductForCheckout(
                        cartItem.productId(),
                        cartItem.quantity());                order.addItem(new OrderItem(product.id(), product.name(), cartItem.quantity(), product.price()));
            } catch (com.company.shop.module.product.exception.ProductInsufficientStockException ex) {
                throw new OrderInsufficientStockException(cartItem.productId(), cartItem.quantity(),
                        ex.getAvailableQuantity());
            }
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
