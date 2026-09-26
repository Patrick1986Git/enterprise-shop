package com.company.shop.module.notification.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class NotificationDeliveryMetrics {

    public NotificationDeliveryMetrics(NotificationRepository repository, MeterRegistry meters) {
        meters.gauge("shop.notification.actionable.count", repository,
                NotificationRepository::countActionable);
        meters.gauge("shop.notification.actionable.oldest.age.seconds", repository,
                NotificationRepository::findOldestActionableAgeSeconds);
        meters.gauge("shop.notification.failed.count", repository,
                value -> value.countByStatus(NotificationStatus.FAILED));
        meters.gauge("shop.notification.failed.oldest.last_attempt.age.seconds", repository,
                NotificationRepository::findOldestFailedLastAttemptAgeSeconds);
    }
}
