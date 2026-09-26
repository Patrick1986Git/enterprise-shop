package com.company.shop.module.order.outbox;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class OutboxEventMetrics {

    public OutboxEventMetrics(OutboxEventRepository repository, MeterRegistry meters, Clock clock) {
        meters.gauge("shop.outbox.actionable.count", repository,
                OutboxEventRepository::countActionable);
        meters.gauge("shop.outbox.actionable.oldest.age.seconds", repository,
                value -> ageSeconds(value.findOldestActionableAt(), clock));
        meters.gauge("shop.outbox.dead_letter.count", repository,
                value -> value.countByStatus(OutboxEventStatus.DEAD_LETTER));
        meters.gauge("shop.outbox.dead_letter.oldest.age.seconds", repository,
                value -> ageSeconds(value.findOldestDeadLetterAt(), clock));
    }

    private static double ageSeconds(java.util.Optional<java.time.Instant> oldest, Clock clock) {
        return oldest.map(value -> (double) Math.max(0, Duration.between(value, clock.instant()).toSeconds()))
                .orElse(0.0);
    }
}
