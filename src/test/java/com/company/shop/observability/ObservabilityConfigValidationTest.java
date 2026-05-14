package com.company.shop.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ObservabilityConfigValidationTest {

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void checkoutMetricsAreExposed() {
        meterRegistry.counter("shop.checkout.total", "result", "attempt").increment();

        assertThat(meterRegistry.get("shop.checkout.total").tag("result", "attempt").counter()).isNotNull();
    }

    @Test
    void paymentMetricsAreExposed() {
        meterRegistry.counter("shop.payment_intent.total", "result", "created").increment();

        assertThat(meterRegistry.get("shop.payment_intent.total").tag("result", "created").counter()).isNotNull();
    }

    @Test
    void webhookMetricsAreExposed() {
        meterRegistry.counter("shop.webhook.total", "result", "received").increment();

        assertThat(meterRegistry.get("shop.webhook.total").tag("result", "received").counter()).isNotNull();
    }
}
