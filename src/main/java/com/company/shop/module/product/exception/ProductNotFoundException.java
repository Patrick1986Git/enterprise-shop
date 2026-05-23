package com.company.shop.module.product.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(UUID productId) {
        super(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "error.business.product.notFound", new Object[] {productId}, "Product not found: " + productId);
    }

    public ProductNotFoundException(String slug) {
        super(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "error.business.product.notFoundBySlug", new Object[] {slug}, "Product not found for slug: " + slug);
    }
}
