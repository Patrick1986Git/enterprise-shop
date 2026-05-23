package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when attempting to place an order from an empty cart.
 */
public class EmptyCartCheckoutException extends BusinessException {

    public EmptyCartCheckoutException() {
        super(HttpStatus.CONFLICT, "ORDER_CART_EMPTY", "error.business.order.emptyCartCheckout", new Object[0], "Cannot place order: cart is empty.");
    }
}
