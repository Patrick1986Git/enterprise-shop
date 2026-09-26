package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NotificationDeliveryMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void constructor_shouldRegisterExactUntaggedGaugeSetWithCurrentState() {
        when(repository.countActionable()).thenReturn(5L);
        when(repository.findOldestActionableAgeSeconds()).thenReturn(120.0);
        when(repository.countByStatus(NotificationStatus.FAILED)).thenReturn(3L);
        when(repository.findOldestFailedLastAttemptAgeSeconds()).thenReturn(600.0);

        new NotificationDeliveryMetrics(repository, meters);

        assertGauge("shop.notification.actionable.count", 5);
        assertGauge("shop.notification.actionable.oldest.age.seconds", 120);
        assertGauge("shop.notification.failed.count", 3);
        assertGauge("shop.notification.failed.oldest.last_attempt.age.seconds", 600);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test
    void ageGauges_shouldReturnZeroForNoWorkAndClockAnomalies() {
        when(repository.findOldestActionableAgeSeconds()).thenReturn(0.0);
        when(repository.findOldestFailedLastAttemptAgeSeconds()).thenReturn(0.0);

        new NotificationDeliveryMetrics(repository, meters);

        assertGauge("shop.notification.actionable.oldest.age.seconds", 0);
        assertGauge("shop.notification.failed.oldest.last_attempt.age.seconds", 0);
    }

    private void assertGauge(String name, double expected) {
        Gauge gauge = meters.get(name).gauge();
        assertThat(gauge.value()).isEqualTo(expected);
    }
}
