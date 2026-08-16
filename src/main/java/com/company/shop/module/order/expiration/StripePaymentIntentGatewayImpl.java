package com.company.shop.module.order.expiration;

import org.springframework.stereotype.Component;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;

@Component
public class StripePaymentIntentGatewayImpl implements StripePaymentIntentGateway {
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
