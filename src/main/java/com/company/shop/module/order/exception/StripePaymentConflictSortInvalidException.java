package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;
import com.company.shop.common.exception.BusinessException;

public class StripePaymentConflictSortInvalidException extends BusinessException {
    public StripePaymentConflictSortInvalidException(String property) {
        super(HttpStatus.BAD_REQUEST, "Unsupported conflict sort: " + property,
                "STRIPE_PAYMENT_CONFLICT_SORT_INVALID");
    }
}
