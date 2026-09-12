package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.StripePaymentConflictException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;

class StripeWebhookProcessorTest {
    private final PaymentService payments = mock(PaymentService.class);
    private final StripePaymentConflictRecorder recorder = mock(StripePaymentConflictRecorder.class);
    private final StripeWebhookProcessor processor = new StripeWebhookProcessor(payments, recorder);

    @Test
    void process_shouldPersistAuthenticatedTerminalConflictAndRethrow() {
        StripePaymentConflictException conflict = new StripePaymentConflictException(UUID.randomUUID(),
                UUID.randomUUID(), "pi_1", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                "evt_1", "payment_intent.succeeded");
        doThrow(conflict).when(payments).handleWebhook("payload", "signature");

        assertThatThrownBy(() -> processor.process("payload", "signature")).isSameAs(conflict);

        verify(recorder).record(conflict);
    }

    @Test
    void process_shouldNotPersistUntrustedFailure() {
        WebhookSignatureInvalidException failure = new WebhookSignatureInvalidException();
        doThrow(failure).when(payments).handleWebhook("payload", "signature");

        assertThatThrownBy(() -> processor.process("payload", "signature")).isSameAs(failure);

        verifyNoInteractions(recorder);
    }
}
