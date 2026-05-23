package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductDataInvalidException extends BusinessException {

    public ProductDataInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, "PRODUCT_DATA_INVALID", "error.business.product.dataInvalid", new Object[] { message }, message);
    }
}
