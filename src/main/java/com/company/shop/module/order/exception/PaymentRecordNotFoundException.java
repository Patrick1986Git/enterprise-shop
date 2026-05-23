package com.company.shop.module.order.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when expected payment record does not exist for a known order.
 */
public class PaymentRecordNotFoundException extends BusinessException {

    public PaymentRecordNotFoundException(UUID orderId) {
        super(HttpStatus.INTERNAL_SERVER_ERROR,
                "PAYMENT_RECORD_MISSING",
                "error.business.payment.recordNotFound",
                new Object[] { orderId },
                "Payment record not found for order: " + orderId);
    }
}
