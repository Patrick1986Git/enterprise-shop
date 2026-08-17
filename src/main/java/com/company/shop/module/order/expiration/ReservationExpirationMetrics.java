package com.company.shop.module.order.expiration;

import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class ReservationExpirationMetrics {
    public ReservationExpirationMetrics(ReservationExpirationWorkRepository repository, MeterRegistry meters, Clock clock) {
        meters.gauge("shop.order.reservation_expiration.failed.count", repository,
                value -> value.countByStatus(ReservationExpirationWorkStatus.FAILED));
        meters.gauge("shop.order.reservation_expiration.failed.oldest.age.seconds", repository,
                value -> value.findOldestFailedAt()
                        .map(oldest -> (double) Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()))
                        .orElse(0.0));
    }
}
