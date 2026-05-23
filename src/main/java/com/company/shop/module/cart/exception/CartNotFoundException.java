package com.company.shop.module.cart.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class CartNotFoundException extends BusinessException {

    public CartNotFoundException(UUID userId) {
        super(HttpStatus.NOT_FOUND,
              "CART_NOT_FOUND",
              "error.business.cart.notFound",
              new Object[] { userId },
              "Cart not found for user: " + userId);
    }
}