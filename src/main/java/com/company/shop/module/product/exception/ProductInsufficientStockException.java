package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductInsufficientStockException extends BusinessException {

    private final String productName;
    private final int requestedQuantity;
    private final int availableQuantity;

    public ProductInsufficientStockException(String productName, int requestedQuantity, int availableQuantity) {
        super(HttpStatus.CONFLICT,
                "PRODUCT_INSUFFICIENT_STOCK",
                "error.business.product.insufficientStock",
                new Object[] { productName, requestedQuantity, availableQuantity },
                "Insufficient stock for product: " + productName + ". Requested: " + requestedQuantity
                        + ", available: " + availableQuantity);
        this.productName = productName;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public String getProductName() {
        return productName;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
