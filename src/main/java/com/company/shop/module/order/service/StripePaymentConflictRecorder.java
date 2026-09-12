package com.company.shop.module.order.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;
import com.company.shop.module.order.exception.StripePaymentConflictException;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;

@Service
public class StripePaymentConflictRecorder {
    private final StripePaymentConflictRepository repository;
    private final Clock clock;
    private final MeterRegistry meters;
    public StripePaymentConflictRecorder(StripePaymentConflictRepository repository, Clock clock, MeterRegistry meters) {
        this.repository = repository; this.clock = clock; this.meters = meters;
    }
    @Transactional
    public void record(StripePaymentConflictException conflict) {
        int inserted = repository.insertIgnoreDuplicate(java.util.UUID.randomUUID(), conflict.getOrderId(), conflict.getPaymentId(),
                conflict.getStripeEventId(), conflict.getProviderPaymentId(), conflict.getEventType(),
                conflict.getOrderStatus().name(), conflict.getPaymentStatus().name(), conflict.getReason().name(),
                Instant.now(clock));
        if (inserted == 1) {
            meters.counter("shop.stripe.payment_conflict.total", "reason", conflict.getReason().name()).increment();
        }
    }
}
