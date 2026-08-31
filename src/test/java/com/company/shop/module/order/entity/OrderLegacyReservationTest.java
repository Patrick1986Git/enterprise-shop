package com.company.shop.module.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderLegacyReservationTest {
    private static final Instant DEADLINE = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void adoptLegacyReservation_shouldSetDeadlineForUnmanagedNewOrder() {
        Order order = order(null);

        order.adoptLegacyReservation(DEADLINE);

        assertThat(order.getReservationExpiresAt()).isEqualTo(DEADLINE);
    }

    @Test
    void adoptLegacyReservation_shouldRejectTerminalOrder() {
        Order order = order(null);
        order.cancelIfNew();

        assertThatThrownBy(() -> order.adoptLegacyReservation(DEADLINE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adoptLegacyReservation_shouldRejectExistingDeadline() {
        Order order = order(DEADLINE.minusSeconds(60));

        assertThatThrownBy(() -> order.adoptLegacyReservation(DEADLINE))
                .isInstanceOf(IllegalStateException.class);
    }

    private Order order(Instant deadline) {
        return new Order(UUID.randomUUID(), "legacy@example.com", "legacy-key", deadline);
    }
}
