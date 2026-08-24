package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OrderAmountInvalidException extends BusinessException {

    public OrderAmountInvalidException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "ORDER_AMOUNT_INVALID",
                "error.business.order.amountInvalid",
                new Object[0],
                "Order total must be a positive PLN amount with at most 10 integer digits and 2 fraction digits");
    }
}
