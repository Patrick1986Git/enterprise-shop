package com.company.shop.module.order.expiration;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;

@Component
public class StripePaymentIntentGatewayImpl implements StripePaymentIntentGateway {
    @Override
    public PaymentIntent create(UUID orderId, BigDecimal amount, String idempotencyKey) throws Exception {
        var params = PaymentIntentCreateParams.builder().setAmount(amount.movePointRight(2).longValue())
                .setCurrency("pln").putMetadata("orderId", orderId.toString()).build();
        var options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return PaymentIntent.create(params, options);
    }
    @Override
    public PaymentIntent retrieve(String paymentIntentId) throws Exception {
        return PaymentIntent.retrieve(paymentIntentId);
    }
    @Override
    public PaymentIntent cancelAsAbandoned(PaymentIntent intent, String idempotencyKey) throws Exception {
        var params = PaymentIntentCancelParams.builder()
                .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED).build();
        var options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return intent.cancel(params, options);
    }
}
