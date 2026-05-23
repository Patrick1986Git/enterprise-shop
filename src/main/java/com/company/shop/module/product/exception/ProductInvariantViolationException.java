package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductInvariantViolationException extends BusinessException {

    public ProductInvariantViolationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR,
                "PRODUCT_INVARIANT_VIOLATION",
                "error.business.product.invariantViolation",
                new Object[] { message },
                message);
    }
}
