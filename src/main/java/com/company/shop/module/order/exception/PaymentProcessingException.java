package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when payment intent creation fails.
 */
public class PaymentProcessingException extends BusinessException {

    public PaymentProcessingException(String message) {
        super(HttpStatus.BAD_GATEWAY, "PAYMENT_PROCESSING_ERROR", "error.business.payment.processingFailed", new Object[] { message }, message);
    }
}
