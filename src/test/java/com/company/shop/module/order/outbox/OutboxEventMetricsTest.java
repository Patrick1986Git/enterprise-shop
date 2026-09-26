package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxEventMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void constructor_shouldRegisterExactUntaggedGaugeSetWithCurrentState() {
        when(repository.countActionable()).thenReturn(4L);
        when(repository.findOldestActionableAt()).thenReturn(Optional.of(NOW.minusSeconds(90)));
        when(repository.countByStatus(OutboxEventStatus.DEAD_LETTER)).thenReturn(2L);
        when(repository.findOldestDeadLetterAt()).thenReturn(Optional.of(NOW.minusSeconds(300)));

        new OutboxEventMetrics(repository, meters, clock);

        assertGauge("shop.outbox.actionable.count", 4);
        assertGauge("shop.outbox.actionable.oldest.age.seconds", 90);
        assertGauge("shop.outbox.dead_letter.count", 2);
        assertGauge("shop.outbox.dead_letter.oldest.age.seconds", 300);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test
    void ageGauges_shouldReturnZeroForNoWorkAndClockAnomalies() {
        when(repository.findOldestActionableAt()).thenReturn(Optional.empty());
        when(repository.findOldestDeadLetterAt()).thenReturn(Optional.of(NOW.plusSeconds(30)));

        new OutboxEventMetrics(repository, meters, clock);

        assertGauge("shop.outbox.actionable.oldest.age.seconds", 0);
        assertGauge("shop.outbox.dead_letter.oldest.age.seconds", 0);
    }

    private void assertGauge(String name, double expected) {
        Gauge gauge = meters.get(name).gauge();
        assertThat(gauge.value()).isEqualTo(expected);
    }
}
