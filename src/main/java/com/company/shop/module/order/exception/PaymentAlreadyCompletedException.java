package com.company.shop.module.order.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when client tries to create or access payment intent for already completed payment.
 */
public class PaymentAlreadyCompletedException extends BusinessException {

    public PaymentAlreadyCompletedException(UUID orderId) {
        super(HttpStatus.CONFLICT, "PAYMENT_ALREADY_COMPLETED", "error.business.payment.alreadyCompleted", new Object[] {orderId}, "Payment already completed for order: " + orderId);
    }
}
