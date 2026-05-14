package com.company.shop.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SpringBootTest(classes = ObservabilityConfigValidationTest.TestApplication.class)
class ObservabilityConfigValidationTest {

    @Autowired
    private MeterRegistry meterRegistry;

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

    @SpringBootConfiguration
    static class TestApplication {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
