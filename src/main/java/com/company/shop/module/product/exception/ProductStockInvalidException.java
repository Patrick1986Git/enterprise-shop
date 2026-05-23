package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductStockInvalidException extends BusinessException {

    public ProductStockInvalidException(int stock) {
        super(HttpStatus.BAD_REQUEST,
                "PRODUCT_STOCK_INVALID",
                "error.business.product.stockInvalid",
                new Object[] { stock },
                "Product stock cannot be negative: " + stock);
    }

    public ProductStockInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST,
                "PRODUCT_STOCK_INVALID",
                "error.business.product.stockInvalid",
                new Object[] { message },
                message);
    }

    public ProductStockInvalidException(String operation, int quantity) {
        super(HttpStatus.BAD_REQUEST,
                "PRODUCT_STOCK_INVALID",
                "error.business.product.stockInvalid",
                new Object[] { operation, quantity },
                "Invalid stock operation '" + operation + "' for quantity: " + quantity);
    }
}
