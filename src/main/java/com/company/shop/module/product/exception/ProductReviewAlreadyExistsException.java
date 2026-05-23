package com.company.shop.module.product.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductReviewAlreadyExistsException extends BusinessException {

    public ProductReviewAlreadyExistsException(UUID productId) {
        super(HttpStatus.CONFLICT,
                "PRODUCT_REVIEW_ALREADY_EXISTS",
                "error.business.productReview.alreadyExists",
                new Object[] { productId },
                "Review already exists for product: " + productId);
    }
}
