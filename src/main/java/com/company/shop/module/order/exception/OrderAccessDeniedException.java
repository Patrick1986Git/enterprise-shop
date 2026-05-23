package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when a user attempts to access an order without required permissions.
 */
public class OrderAccessDeniedException extends BusinessException {

    public OrderAccessDeniedException() {
        super(HttpStatus.FORBIDDEN, "ORDER_ACCESS_DENIED", "error.business.order.accessDenied", new Object[0], "You are not authorized to access this order.");
    }
}
