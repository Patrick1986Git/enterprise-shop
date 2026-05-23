package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when a discount code is missing, expired, or no longer valid.
 */
public class DiscountCodeInvalidException extends BusinessException {

    public DiscountCodeInvalidException(String code) {
        super(HttpStatus.CONFLICT, "DISCOUNT_CODE_INVALID", "error.business.order.discountCodeInvalid", new Object[] { code }, "Invalid or expired discount code: " + code);
    }
}
