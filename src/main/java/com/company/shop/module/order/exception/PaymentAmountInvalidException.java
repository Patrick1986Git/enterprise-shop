package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class PaymentAmountInvalidException extends BusinessException {

    public PaymentAmountInvalidException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_AMOUNT_INVALID",
                "error.business.payment.amountInvalid", new Object[0],
                "Local PLN payment amount cannot be represented exactly in grosz");
    }
}
