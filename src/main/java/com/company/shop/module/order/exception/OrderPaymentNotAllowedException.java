package com.company.shop.module.order.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;
import com.company.shop.module.order.entity.OrderStatus;

public class OrderPaymentNotAllowedException extends BusinessException {

    public OrderPaymentNotAllowedException(UUID orderId, OrderStatus status) {
        super(HttpStatus.CONFLICT,
                "ORDER_PAYMENT_NOT_ALLOWED",
                "error.business.order.paymentNotAllowed",
                new Object[] { orderId, status },
                "Payment is not allowed for order " + orderId + " in status " + status);
    }
}
