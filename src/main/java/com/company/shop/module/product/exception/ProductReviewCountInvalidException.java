package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductReviewCountInvalidException extends BusinessException {

    public ProductReviewCountInvalidException(int reviewCount) {
        super(HttpStatus.INTERNAL_SERVER_ERROR,
                "PRODUCT_REVIEW_INVARIANT_VIOLATION",
                "error.business.productReview.countInvalid",
                new Object[] { reviewCount },
                "Product review count cannot be negative: " + reviewCount);
    }

    public ProductReviewCountInvalidException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR,
                "PRODUCT_REVIEW_INVARIANT_VIOLATION",
                "error.business.productReview.countInvalid",
                new Object[] { message },
                message);
    }
}
