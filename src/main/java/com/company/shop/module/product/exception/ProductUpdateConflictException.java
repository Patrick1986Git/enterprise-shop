package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductUpdateConflictException extends BusinessException {

    public ProductUpdateConflictException() {
        super(HttpStatus.CONFLICT, "PRODUCT_UPDATE_CONFLICT", null, new Object[0],
                "Product changed since it was read; reload it before updating.");
    }
}
