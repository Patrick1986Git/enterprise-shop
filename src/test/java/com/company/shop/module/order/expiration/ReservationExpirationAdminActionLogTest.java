package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReservationExpirationAdminActionLogTest {
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID WORK_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void adoption_shouldCreateCompleteNormalizedAction() {
        var log = ReservationExpirationAdminActionLog.adoption(
                ORDER_ID, WORK_ID, "  admin@example.com  ", CREATED_AT);

        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getWorkId()).isEqualTo(WORK_ID);
        assertThat(log.getActionType()).isEqualTo(ReservationExpirationAdminActionType.LEGACY_ADOPTION);
        assertThat(log.getOutcome()).isEqualTo(ReservationExpirationAdminActionOutcome.ADOPTED);
        assertThat(log.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(log.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void recovery_shouldAcceptBothRecoveryOutcomes() {
        assertThat(recovery(ReservationExpirationAdminActionOutcome.REQUEUED).getOutcome())
                .isEqualTo(ReservationExpirationAdminActionOutcome.REQUEUED);
        assertThat(recovery(ReservationExpirationAdminActionOutcome.TERMINAL_NOOP).getOutcome())
                .isEqualTo(ReservationExpirationAdminActionOutcome.TERMINAL_NOOP);
    }

    @Test
    void recovery_shouldRejectAdoptionOutcome() {
        assertThatThrownBy(() -> recovery(ReservationExpirationAdminActionOutcome.ADOPTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Recovery outcome must describe a recovery result");
    }

    @Test
    void factories_shouldRejectMissingRequiredData() {
        assertThatThrownBy(() -> ReservationExpirationAdminActionLog.adoption(
                null, WORK_ID, "admin@example.com", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Order id is required");
        assertThatThrownBy(() -> ReservationExpirationAdminActionLog.adoption(
                ORDER_ID, null, "admin@example.com", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Reservation expiration work id is required");
        assertThatThrownBy(() -> ReservationExpirationAdminActionLog.adoption(
                ORDER_ID, WORK_ID, "  ", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reservation expiration admin action actor email is required");
        assertThatThrownBy(() -> ReservationExpirationAdminActionLog.adoption(
                ORDER_ID, WORK_ID, "admin@example.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reservation expiration admin action timestamp is required");
    }

    private ReservationExpirationAdminActionLog recovery(ReservationExpirationAdminActionOutcome outcome) {
        return ReservationExpirationAdminActionLog.recovery(
                ORDER_ID, WORK_ID, outcome, "admin@example.com", CREATED_AT);
    }
}
