package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxEventAdminActionLogTest {

    @Test
    void requeue_shouldCreateRequeueActionLog() {
        UUID outboxEventId = UUID.randomUUID();
        Instant beforeCreate = Instant.now();

        OutboxEventAdminActionLog log = OutboxEventAdminActionLog.requeue(outboxEventId, "admin@example.com");

        assertThat(log.getOutboxEventId()).isEqualTo(outboxEventId);
        assertThat(log.getActionType()).isEqualTo(OutboxEventAdminActionType.REQUEUE);
        assertThat(log.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(log.getCreatedAt()).isNotNull().isAfterOrEqualTo(beforeCreate);
        assertThat(log.getDetails()).isEqualTo("Manual requeue requested for failed outbox event.");
    }

    @Test
    void requeue_shouldTrimActorEmail() {
        OutboxEventAdminActionLog log = OutboxEventAdminActionLog.requeue(
                UUID.randomUUID(),
                "  admin@example.com  ");

        assertThat(log.getActorEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void requeue_shouldRejectNullOutboxEventId() {
        assertThatThrownBy(() -> OutboxEventAdminActionLog.requeue(null, "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Outbox event id is required");
    }

    @Test
    void requeue_shouldRejectBlankActorEmail() {
        assertThatThrownBy(() -> OutboxEventAdminActionLog.requeue(UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Outbox event admin action actor email is required");
    }
}
