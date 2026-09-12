package com.company.shop.module.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class StripePaymentConflictDispositionTest {
    @Test
    void record_shouldCreateTrimmedAppendOnlyAction() {
        UUID conflictId = UUID.randomUUID();
        StripePaymentConflictDisposition disposition = StripePaymentConflictDisposition.record(
                conflictId, StripePaymentConflictDispositionType.ESCALATED, " admin@example.com ");

        assertThat(disposition.getConflictId()).isEqualTo(conflictId);
        assertThat(disposition.getActionType()).isEqualTo(StripePaymentConflictDispositionType.ESCALATED);
        assertThat(disposition.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(disposition.getCreatedAt()).isNotNull();
    }

    @Test
    void record_shouldRejectMissingRequiredEvidence() {
        UUID conflictId = UUID.randomUUID();
        assertThatThrownBy(() -> StripePaymentConflictDisposition.record(
                null, StripePaymentConflictDispositionType.ACKNOWLEDGED, "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StripePaymentConflictDisposition.record(conflictId, null, "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StripePaymentConflictDisposition.record(
                conflictId, StripePaymentConflictDispositionType.ACKNOWLEDGED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StripePaymentConflictDisposition.record(
                conflictId, StripePaymentConflictDispositionType.ACKNOWLEDGED, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
