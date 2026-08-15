package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationExpirationWorkTest {
    @Test
    void retry_shouldRetainVisibleFailedStateAfterMaximumAttemptsWithoutCompleting() {
        Instant due = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), due);
        UUID token = work.claim(due, due.plusSeconds(60));
        work.retry(token, due.plusSeconds(30), "provider unavailable", 1);
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
        assertThat(work.getAttempts()).isEqualTo(1);
        assertThat(work.getLastError()).isEqualTo("provider unavailable");
    }
}
