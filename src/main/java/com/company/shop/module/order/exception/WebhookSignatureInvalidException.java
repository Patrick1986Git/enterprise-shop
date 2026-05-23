package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when Stripe webhook signature or payload is invalid.
 */
public class WebhookSignatureInvalidException extends BusinessException {

    public WebhookSignatureInvalidException() {
        this("Invalid Stripe webhook signature or payload.");
    }

    public WebhookSignatureInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, "STRIPE_WEBHOOK_SIGNATURE_INVALID", "error.business.webhook.signatureInvalid", new Object[] { message }, message);
    }
}
