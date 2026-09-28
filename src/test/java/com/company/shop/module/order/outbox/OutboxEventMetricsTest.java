package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxEventMetricsTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void constructor_shouldRegisterExactUntaggedGaugeSetWithCurrentState() {
        when(repository.countActionable()).thenReturn(4L);
        when(repository.findOldestActionableAgeSeconds()).thenReturn(90.0);
        when(repository.countByStatus(OutboxEventStatus.DEAD_LETTER)).thenReturn(2L);
        when(repository.findOldestDeadLetterAgeSeconds()).thenReturn(300.0);

        new OutboxEventMetrics(repository, meters);

        assertGauge("shop.outbox.actionable.count", 4);
        assertGauge("shop.outbox.actionable.oldest.age.seconds", 90);
        assertGauge("shop.outbox.dead_letter.count", 2);
        assertGauge("shop.outbox.dead_letter.oldest.age.seconds", 300);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test
    void ageGauges_shouldReturnZeroForNoWorkAndClockAnomalies() {
        when(repository.findOldestActionableAgeSeconds()).thenReturn(0.0);
        when(repository.findOldestDeadLetterAgeSeconds()).thenReturn(0.0);

        new OutboxEventMetrics(repository, meters);

        assertGauge("shop.outbox.actionable.oldest.age.seconds", 0);
        assertGauge("shop.outbox.dead_letter.oldest.age.seconds", 0);
    }

    private void assertGauge(String name, double expected) {
        Gauge gauge = meters.get(name).gauge();
        assertThat(gauge.value()).isEqualTo(expected);
    }
}
