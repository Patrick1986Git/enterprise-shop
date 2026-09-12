package com.company.shop.module.order.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.exception.PaymentRecordNotFoundException;
import com.company.shop.module.order.exception.StripePaymentConflictException;
import com.company.shop.module.order.exception.WebhookProcessingException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.api.internal.ReservedInventoryItem;
import com.stripe.model.PaymentIntent;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class PaymentTerminalTransitionService {
    private static final Logger log = LoggerFactory.getLogger(PaymentTerminalTransitionService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartCheckoutFacade cartCheckoutFacade;
    private final ProductCatalogFacade productCatalogFacade;

    public PaymentTerminalTransitionService(OrderRepository orderRepository, PaymentRepository paymentRepository,
            CartCheckoutFacade cartCheckoutFacade, ProductCatalogFacade productCatalogFacade) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.cartCheckoutFacade = cartCheckoutFacade;
        this.productCatalogFacade = productCatalogFacade;
    }

    @Transactional
    public boolean convergeSucceeded(UUID orderId, PaymentIntent intent) {
        return convergeSucceeded(orderId, intent, null, null);
    }

    @Transactional
    public boolean convergeSucceeded(UUID orderId, PaymentIntent intent, String stripeEventId, String eventType) {
        Order order = lockOrder(orderId);
        Payment payment = lockPayment(orderId);
        validatePaymentIntentMatchesOrder(intent, order);
        attachOrValidateProviderPayment(intent, payment);
        if (order.getStatus() != OrderStatus.NEW) {
            if ((order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.SHIPPED)
                    && payment.getStatus() == PaymentStatus.COMPLETED) {
                return false;
            }
            log.error("Succeeded provider payment conflicts with local state orderId={} paymentId={} "
                    + "providerPaymentId={} orderStatus={} paymentStatus={}",
                    order.getId(), payment.getId(), payment.getProviderPaymentId(), order.getStatus(),
                    payment.getStatus());
            throw new StripePaymentConflictException(
                    order.getId(), payment.getId(), intent.getId(), order.getStatus(), payment.getStatus(),
                    stripeEventId, eventType);
        }
        order.markAsPaid();
        payment.markAsCompleted();
        orderRepository.save(order);
        paymentRepository.save(payment);
        cartCheckoutFacade.reconcileCartAfterSuccessfulPayment(order.getUserId(), order.getItems().stream()
                .map(item -> new CartCheckoutItem(item.getProductId(), item.getQuantity())).toList());
        return true;
    }

    @Transactional
    public int convergeCanceled(UUID orderId, PaymentIntent intent) {
        Order order = lockOrder(orderId);
        Payment payment = lockPayment(orderId);
        validatePaymentIntentMatchesOrder(intent, order);
        attachOrValidateProviderPayment(intent, payment);
        if (order.getStatus() != OrderStatus.NEW) return 0;
        var reservedItems = order.getItems().stream()
                .map(item -> new ReservedInventoryItem(item.getProductId(), item.getQuantity())).toList();
        productCatalogFacade.releaseReservedInventory(reservedItems);
        order.cancelIfNew();
        payment.markAsFailed();
        orderRepository.save(order);
        paymentRepository.save(payment);
        return reservedItems.stream().mapToInt(ReservedInventoryItem::quantity).sum();
    }

    @Transactional
    public boolean convergeFailed(UUID orderId, PaymentIntent intent) {
        Order order = lockOrder(orderId);
        Payment payment = lockPayment(orderId);
        validatePaymentIntentMatchesOrder(intent, order);
        attachOrValidateProviderPayment(intent, payment);
        if (payment.getStatus() == PaymentStatus.COMPLETED) return false;
        payment.markAsFailed();
        paymentRepository.save(payment);
        return true;
    }

    private Order lockOrder(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
    private Payment lockPayment(UUID orderId) {
        return paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentRecordNotFoundException(orderId));
    }
    private void attachOrValidateProviderPayment(PaymentIntent intent, Payment payment) {
        if (payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().isBlank()
                && !payment.getProviderPaymentId().equals(intent.getId())) {
            throw new WebhookSignatureInvalidException("PaymentIntent id does not match the attached provider payment id.");
        }
        if (payment.getProviderPaymentId() == null || payment.getProviderPaymentId().isBlank()) {
            payment.attachProviderPayment(intent.getId(), intent.getClientSecret());
        }
    }
    private void validatePaymentIntentMatchesOrder(PaymentIntent intent, Order order) {
        Long amount = intent.getAmountReceived() != null && intent.getAmountReceived() > 0
                ? intent.getAmountReceived() : intent.getAmount();
        long expectedAmount = StripeMinorUnitConverter.fromPln(order.getTotalAmount());
        if (amount == null) {
            throw new WebhookSignatureInvalidException("PaymentIntent does not contain payment amount.");
        }
        if (amount.longValue() != expectedAmount) {
            throw new WebhookSignatureInvalidException("PaymentIntent payment amount does not match order total.");
        }
        if (intent.getCurrency() == null || !"pln".equals(intent.getCurrency().toLowerCase(Locale.ROOT))) {
            throw new WebhookSignatureInvalidException("PaymentIntent currency does not match expected currency.");
        }
        if (order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new WebhookProcessingException("Order amount is invalid for payment reconciliation.");
        }
    }
}
