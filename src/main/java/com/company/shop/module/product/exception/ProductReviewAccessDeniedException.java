package com.company.shop.module.product.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductReviewAccessDeniedException extends BusinessException {

    public ProductReviewAccessDeniedException() {
        super(HttpStatus.FORBIDDEN,
                "PRODUCT_REVIEW_ACCESS_DENIED",
                "error.business.productReview.accessDenied",
                new Object[0],
                "You are not allowed to delete this product review.");
    }
}
