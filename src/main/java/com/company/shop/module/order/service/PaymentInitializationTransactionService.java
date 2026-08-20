package com.company.shop.module.order.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.*;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class PaymentInitializationTransactionService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    public PaymentInitializationTransactionService(OrderRepository orders, PaymentRepository payments) {
        this.orders = orders; this.payments = payments;
    }
    @Transactional
    public PaymentInitialization prepare(Order order) {
        UUID orderId = order.getId();
        if (order.getStatus() != OrderStatus.NEW) throw new OrderPaymentNotAllowedException(orderId, order.getStatus());
        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentRecordNotFoundException(orderId));
        if (payment.getStatus() == PaymentStatus.COMPLETED) throw new PaymentAlreadyCompletedException(orderId);
        String secret = hasText(payment.getProviderPaymentId()) && hasText(payment.getClientSecret())
                ? payment.getClientSecret() : null;
        return new PaymentInitialization(orderId, order.getTotalAmount(), secret);
    }
    @Transactional
    public void attach(UUID orderId, String providerId, String clientSecret) {
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentRecordNotFoundException(orderId));
        if (hasText(payment.getProviderPaymentId()) && !payment.getProviderPaymentId().equals(providerId)) {
            throw new PaymentProcessingException("Provider payment identity mismatch for order: " + orderId);
        }
        payment.attachProviderPayment(providerId, clientSecret);
        if (order.getStatus() == OrderStatus.NEW) payment.markAsPending();
        payments.save(payment);
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
