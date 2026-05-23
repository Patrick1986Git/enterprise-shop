package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when Stripe webhook processing fails.
 */
public class WebhookProcessingException extends BusinessException {

    public WebhookProcessingException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "STRIPE_WEBHOOK_PROCESSING_ERROR", "error.business.webhook.processingFailed", new Object[] { message }, message);
    }
}
