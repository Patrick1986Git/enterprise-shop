package com.company.shop.module.order.expiration;

import com.stripe.model.PaymentIntent;

public interface StripePaymentIntentGateway {
    PaymentIntent retrieve(String paymentIntentId) throws Exception;
    PaymentIntent cancelAsAbandoned(PaymentIntent intent, String idempotencyKey) throws Exception;
}
