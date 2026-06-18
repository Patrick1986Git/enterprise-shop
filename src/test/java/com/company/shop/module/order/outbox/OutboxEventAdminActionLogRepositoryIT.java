package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OutboxEventAdminActionLogRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private OutboxEventAdminActionLogRepository outboxEventAdminActionLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanOutboxEventAdminActionLogs() {
        jdbcTemplate.update("DELETE FROM outbox_event_admin_action_logs");
    }

    @Test
    void saveAndLoad_shouldPersistRequeueActionLog() {
        UUID outboxEventId = UUID.randomUUID();
        OutboxEventAdminActionLog log = OutboxEventAdminActionLog.requeue(outboxEventId, "admin@example.com");

        OutboxEventAdminActionLog savedLog = outboxEventAdminActionLogRepository.saveAndFlush(log);
        entityManager.clear();

        OutboxEventAdminActionLog loadedLog = outboxEventAdminActionLogRepository.findById(savedLog.getId()).orElseThrow();
        assertThat(loadedLog.getOutboxEventId()).isEqualTo(outboxEventId);
        assertThat(loadedLog.getActionType()).isEqualTo(OutboxEventAdminActionType.REQUEUE);
        assertThat(loadedLog.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(loadedLog.getCreatedAt()).isNotNull();
        assertThat(loadedLog.getDetails()).isNull();
    }

    @Test
    void findByOutboxEventIdOrderByCreatedAtDesc_shouldReturnSelectedOutboxEventLogsNewestFirst() {
        UUID selectedOutboxEventId = UUID.randomUUID();
        UUID otherOutboxEventId = UUID.randomUUID();
        OutboxEventAdminActionLog oldestSelectedLog = logAt(
                selectedOutboxEventId,
                "first-admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"));
        OutboxEventAdminActionLog newestSelectedLog = logAt(
                selectedOutboxEventId,
                "second-admin@example.com",
                Instant.parse("2026-01-01T11:00:00Z"));
        OutboxEventAdminActionLog otherOutboxEventLog = logAt(
                otherOutboxEventId,
                "other-admin@example.com",
                Instant.parse("2026-01-01T12:00:00Z"));
        outboxEventAdminActionLogRepository.saveAndFlush(oldestSelectedLog);
        outboxEventAdminActionLogRepository.saveAndFlush(newestSelectedLog);
        outboxEventAdminActionLogRepository.saveAndFlush(otherOutboxEventLog);
        entityManager.clear();

        assertThat(outboxEventAdminActionLogRepository
                .findByOutboxEventIdOrderByCreatedAtDesc(selectedOutboxEventId))
                .extracting(OutboxEventAdminActionLog::getActorEmail)
                .containsExactly("second-admin@example.com", "first-admin@example.com");
    }

    private OutboxEventAdminActionLog logAt(UUID outboxEventId, String actorEmail, Instant createdAt) {
        OutboxEventAdminActionLog log = OutboxEventAdminActionLog.requeue(outboxEventId, actorEmail);
        setCreatedAt(log, createdAt);
        return log;
    }

    private void setCreatedAt(OutboxEventAdminActionLog log, Instant createdAt) {
        try {
            Field createdAtField = OutboxEventAdminActionLog.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(log, createdAt);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
