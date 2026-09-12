package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.StripePaymentConflictException;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StripePaymentConflictRecorderTest {
    @Test
    void record_shouldIncrementFiniteMetricOnlyForNewObservation() {
        StripePaymentConflictRepository repository = mock(StripePaymentConflictRepository.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StripePaymentConflictRecorder recorder = new StripePaymentConflictRecorder(repository,
                Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC), meters);
        StripePaymentConflictException conflict = new StripePaymentConflictException(UUID.randomUUID(),
                UUID.randomUUID(), "pi_1", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                "evt_1", "payment_intent.succeeded");
        when(repository.insertIgnoreDuplicate(any(), eq(conflict.getOrderId()), eq(conflict.getPaymentId()),
                eq("evt_1"), eq("pi_1"), eq("payment_intent.succeeded"), eq("CANCELLED"), eq("FAILED"),
                eq("TERMINAL_STATE_CONTRADICTION"), eq(Instant.parse("2026-09-12T00:00:00Z"))))
                .thenReturn(1, 0);

        recorder.record(conflict);
        recorder.record(conflict);

        assertThat(meters.get("shop.stripe.payment_conflict.total")
                .tag("reason", "TERMINAL_STATE_CONTRADICTION").counter().count()).isEqualTo(1);
    }
}
