package com.company.shop.module.order.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import com.company.shop.common.exception.BusinessException;

public class StripePaymentConflictNotFoundException extends BusinessException {
    public StripePaymentConflictNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Stripe payment conflict not found: " + id,
                "STRIPE_PAYMENT_CONFLICT_NOT_FOUND");
    }
}
