package com.company.shop.module.product.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ProductReviewNotFoundException extends BusinessException {

    public ProductReviewNotFoundException(UUID reviewId) {
        super(HttpStatus.NOT_FOUND,
                "PRODUCT_REVIEW_NOT_FOUND",
                "error.business.productReview.notFound",
                new Object[] { reviewId },
                "Product review not found: " + reviewId);
    }
}
