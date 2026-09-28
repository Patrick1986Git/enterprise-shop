package com.company.shop.module.order.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class OutboxEventMetrics {

    public OutboxEventMetrics(OutboxEventRepository repository, MeterRegistry meters) {
        meters.gauge("shop.outbox.actionable.count", repository,
                OutboxEventRepository::countActionable);
        meters.gauge("shop.outbox.actionable.oldest.age.seconds", repository,
                OutboxEventRepository::findOldestActionableAgeSeconds);
        meters.gauge("shop.outbox.dead_letter.count", repository,
                value -> value.countByStatus(OutboxEventStatus.DEAD_LETTER));
        meters.gauge("shop.outbox.dead_letter.oldest.age.seconds", repository,
                OutboxEventRepository::findOldestDeadLetterAgeSeconds);
    }
}
