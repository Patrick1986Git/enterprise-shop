package com.company.shop.module.order.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when Stripe integration configuration is invalid.
 */
public class StripeConfigurationException extends BusinessException {

    public StripeConfigurationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "STRIPE_CONFIGURATION_ERROR", "error.business.payment.stripeConfiguration", new Object[] { message }, message);
    }
}
