/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service.checkout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.cart.api.internal.CartCheckoutSnapshot;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.DiscountCode;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.exception.DiscountCodeInvalidException;
import com.company.shop.module.order.exception.EmptyCartCheckoutException;
import com.company.shop.module.order.exception.OrderInsufficientStockException;
import com.company.shop.module.order.mapper.OrderMapper;
import com.company.shop.module.order.repository.DiscountCodeRepository;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;

@Component
public class OrderCheckoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderCheckoutProcessor.class);
    private static final String CHECKOUT_METRIC = "shop.checkout.total";
    private static final String RESULT_TAG = "result";

    private final OrderRepository orderRepo;
    private final ProductCatalogFacade productCatalogFacade;
    private final PaymentRepository paymentRepo;
    private final DiscountCodeRepository discountCodeRepo;
    private final CurrentUserFacade currentUserFacade;
    private final CartCheckoutFacade cartCheckoutFacade;
    private final OrderMapper mapper;
    private final PaymentService paymentService;
    private final MeterRegistry meterRegistry;

    public OrderCheckoutProcessor(OrderRepository orderRepo,
            ProductCatalogFacade productCatalogFacade,
            PaymentRepository paymentRepo,
            DiscountCodeRepository discountCodeRepo,
            CurrentUserFacade currentUserFacade,
            CartCheckoutFacade cartCheckoutFacade,
            OrderMapper mapper,
            PaymentService paymentService,
            MeterRegistry meterRegistry) {
        this.orderRepo = orderRepo;
        this.productCatalogFacade = productCatalogFacade;
        this.paymentRepo = paymentRepo;
        this.discountCodeRepo = discountCodeRepo;
        this.currentUserFacade = currentUserFacade;
        this.cartCheckoutFacade = cartCheckoutFacade;
        this.mapper = mapper;
        this.paymentService = paymentService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public OrderResponseDTO placeOrderFromCart(OrderCheckoutRequestDTO request) {
        incrementCheckoutMetric("attempt");
        try {
            Order savedOrder = createPendingOrder(request);
            log.info("Order created during checkout orderId={} userId={} status={} totalAmount={} itemsCount={}",
                    savedOrder.getId(), savedOrder.getUserId(), savedOrder.getStatus(), savedOrder.getTotalAmount(),
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
        CurrentUserSnapshot currentUser = currentUserFacade.getCurrentUser();
        log.info("Checkout started for userId={}", currentUser.id());
        CartCheckoutSnapshot cart = cartCheckoutFacade.getCartForCheckout(currentUser.id());

        if (cart.isEmpty()) {
            throw new EmptyCartCheckoutException();
        }

        Order order = new Order(currentUser.id(), currentUser.email());

        for (CartCheckoutItem cartItem : cart.items()) {
            try {
                CheckoutProduct product = productCatalogFacade.reserveProductForCheckout(
                        cartItem.productId(),
                        cartItem.quantity());

                order.addItem(new OrderItem(
                        product.id(),
                        product.name(),
                        product.sku(),
                        cartItem.quantity(),
                        product.price()));
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
}
