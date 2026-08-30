package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationExpirationWorkTest {
    @Test
    void retry_shouldRetainVisibleFailedStateAfterMaximumAttemptsWithoutCompleting() {
        Instant due = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), due);
        UUID token = work.claim(due, due.plusSeconds(60));
        work.retry(token, due, due.plusSeconds(30), "provider unavailable", 1);
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
        assertThat(work.getAttempts()).isEqualTo(1);
        assertThat(work.getLastError()).isEqualTo("provider unavailable");
        assertThat(work.getFailedAt()).isEqualTo(due);
    }

    @Test
    void requeueFailed_shouldPreserveAttemptsAndFailureEvidence() {
        Instant due = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), due);
        UUID token = work.claim(due, due.plusSeconds(60));
        work.retry(token, due.plusSeconds(1), due.plusSeconds(30), "provider unavailable", 1);

        work.requeueFailed(due.plusSeconds(40), "admin@example.com");

        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
        assertThat(work.getAttempts()).isEqualTo(1);
        assertThat(work.getLastError()).isEqualTo("provider unavailable");
        assertThat(work.getRecoveryCount()).isEqualTo(1);
        assertThat(work.getLastRecoveredBy()).isEqualTo("admin@example.com");
    }

    @Test
    void failedRecoveryTransitions_shouldRejectWorkThatIsNotFailed() {
        Instant now = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), now);

        assertThatThrownBy(() -> work.requeueFailed(now, "admin@example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.completeFailedForTerminalOrder(now, "admin@example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeFailedForTerminalOrder_shouldRecordAuditWithoutResettingAttempts() {
        Instant now = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), now.minusSeconds(60));
        UUID token = work.claim(now.minusSeconds(30), now.plusSeconds(30));
        work.retry(token, now.minusSeconds(10), now, "provider unavailable", 1);

        work.completeFailedForTerminalOrder(now, "admin@example.com");

        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.COMPLETED);
        assertThat(work.getAttempts()).isEqualTo(1);
        assertThat(work.getRecoveryCount()).isEqualTo(1);
        assertThat(work.getLastRecoveredAt()).isEqualTo(now);
    }

    @Test
    void claimBudget_shouldIncludeExactlyOneAttemptPerRecoveryWithoutResettingAttempts() {
        Instant now = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), now.minusSeconds(60));

        assertThat(work.hasClaimBudget(1)).isTrue();
        UUID token = work.claim(now.minusSeconds(30), now.minusSeconds(20));
        assertThat(work.hasClaimBudget(1)).isFalse();
        work.retry(token, now.minusSeconds(10), now, "provider unavailable", 1);
        work.requeueFailed(now, "admin@example.com");

        assertThat(work.getAttempts()).isOne();
        assertThat(work.isRecoveryAuthorized()).isTrue();
        assertThat(work.hasClaimBudget(1)).isTrue();
        work.claim(now, now.plusSeconds(30));
        assertThat(work.isRecoveryAuthorized()).isFalse();
        assertThat(work.hasClaimBudget(1)).isFalse();
    }

    @Test
    void failClaimBudgetExhausted_shouldRejectActiveClaim() {
        Instant now = Instant.parse("2026-08-15T12:00:00Z");
        var work = new ReservationExpirationWork(UUID.randomUUID(), now.minusSeconds(60));
        work.claim(now, now.plusSeconds(30));

        assertThat(work.hasExpiredClaim(now)).isFalse();
        assertThatThrownBy(() -> work.failClaimBudgetExhausted(now, "budget exhausted"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.CLAIMED);
    }
}
