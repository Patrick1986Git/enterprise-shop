package com.company.shop.module.order.service;

import org.springframework.stereotype.Service;
import com.company.shop.module.order.exception.StripePaymentConflictException;

@Service
public class StripeWebhookProcessor {
    private final PaymentService paymentService;
    private final StripePaymentConflictRecorder conflictRecorder;

    public StripeWebhookProcessor(PaymentService paymentService, StripePaymentConflictRecorder conflictRecorder) {
        this.paymentService = paymentService;
        this.conflictRecorder = conflictRecorder;
    }

    public void process(String payload, String signature) {
        try {
            paymentService.handleWebhook(payload, signature);
        } catch (StripePaymentConflictException conflict) {
            conflictRecorder.record(conflict);
            throw conflict;
        }
    }
}
