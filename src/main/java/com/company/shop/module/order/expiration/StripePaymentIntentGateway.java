package com.company.shop.module.order.expiration;

import java.math.BigDecimal;
import java.util.UUID;
import com.stripe.model.PaymentIntent;

public interface StripePaymentIntentGateway {
    PaymentIntent create(UUID orderId, BigDecimal amount, String idempotencyKey) throws Exception;
    PaymentIntent retrieve(String paymentIntentId) throws Exception;
    PaymentIntent cancelAsAbandoned(PaymentIntent intent, String idempotencyKey) throws Exception;
}
