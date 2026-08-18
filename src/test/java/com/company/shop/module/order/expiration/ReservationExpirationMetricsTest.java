package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationMetricsTest {
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    @Mock ReservationExpirationWorkRepository repository;

    @Test
    void gauges_shouldExposeFailedCountAndOldestFailureAgeWithoutHighCardinalityTags() {
        when(repository.countByStatus(ReservationExpirationWorkStatus.FAILED)).thenReturn(3L);
        when(repository.findOldestFailedAt()).thenReturn(Optional.of(NOW.minusSeconds(125)));
        var registry = new SimpleMeterRegistry();

        new ReservationExpirationMetrics(repository, registry, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(registry.get("shop.order.reservation_expiration.failed.count").gauge().value()).isEqualTo(3);
        assertThat(registry.get("shop.order.reservation_expiration.failed.oldest.age.seconds").gauge().value())
                .isEqualTo(125);
    }

    @Test
    void oldestFailureAgeGauge_shouldBeZeroWithoutFailuresAndClampFutureTimestamps() {
        when(repository.findOldestFailedAt()).thenReturn(Optional.empty(), Optional.of(NOW.plusSeconds(30)));
        var registry = new SimpleMeterRegistry();
        new ReservationExpirationMetrics(repository, registry, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(registry.get("shop.order.reservation_expiration.failed.oldest.age.seconds").gauge().value())
                .isZero();
        assertThat(registry.get("shop.order.reservation_expiration.failed.oldest.age.seconds").gauge().value())
                .isZero();
    }
}
