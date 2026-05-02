package com.company.shop.module.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PaymentTest {

    @Test
    void markAsFailed_shouldSetStatusFailedWhenPending() {
        Payment payment = new Payment(null, "STRIPE", BigDecimal.TEN);

        payment.markAsFailed();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void markAsFailed_shouldNotOverrideCompletedPayment() {
        Payment payment = new Payment(null, "STRIPE", BigDecimal.TEN);
        payment.markAsCompleted();

        payment.markAsFailed();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }
}
