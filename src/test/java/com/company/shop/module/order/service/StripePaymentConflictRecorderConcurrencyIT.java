package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.StripePaymentConflictException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;
import com.company.shop.module.order.repository.StripeWebhookEventRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class StripePaymentConflictRecorderConcurrencyIT extends PostgresContainerSupport {
    @Autowired private StripePaymentConflictRecorder recorder;
    @Autowired private StripePaymentConflictRepository conflictRepository;
    @Autowired private StripeWebhookEventRepository webhookEventRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoBean private CartCheckoutFacade cartCheckoutFacade;
    @MockitoBean private ProductCatalogFacade productCatalogFacade;

    @Test
    void record_shouldCommitOneObservationForConcurrentSameEventWithoutTouchingFinancialState() throws Exception {
        String eventId = "evt_concurrent_" + UUID.randomUUID();
        StripePaymentConflictException conflict = new StripePaymentConflictException(UUID.randomUUID(),
                UUID.randomUUID(), "pi_concurrent", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                eventId, "payment_intent.succeeded");
        long ordersBefore = orderRepository.count();
        long paymentsBefore = paymentRepository.count();
        long webhookEventsBefore = webhookEventRepository.count();
        double metricBefore = conflictMetricCount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> recordAfterBarrier(conflict, ready, start));
            Future<?> second = executor.submit(() -> recordAfterBarrier(conflict, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(conflictRepository.countByStripeEventId(eventId)).isOne();
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);
        assertThat(webhookEventRepository.count()).isEqualTo(webhookEventsBefore);
        assertThat(conflictMetricCount() - metricBefore).isEqualTo(1.0);
        verifyNoInteractions(cartCheckoutFacade, productCatalogFacade);
    }

    private void recordAfterBarrier(StripePaymentConflictException conflict, CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
            recorder.record(conflict);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting start barrier", exception);
        }
    }

    private double conflictMetricCount() {
        var counter = meterRegistry.find("shop.stripe.payment_conflict.total")
                .tag("reason", "TERMINAL_STATE_CONTRADICTION").counter();
        return counter == null ? 0.0 : counter.count();
    }
}
