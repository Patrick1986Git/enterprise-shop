package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OutboxEventRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanOutboxEvents() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void save_shouldPersistPendingOutboxEvent() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                "Order",
                aggregateId,
                "TestEvent",
                "{\"orderId\":\"" + aggregateId + "\"}");

        OutboxEvent savedEvent = outboxEventRepository.saveAndFlush(event);

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getAggregateType()).isEqualTo("Order");
        assertThat(savedEvent.getAggregateId()).isEqualTo(aggregateId);
        assertThat(savedEvent.getEventType()).isEqualTo("TestEvent");
        assertThat(savedEvent.getPayload()).contains(aggregateId.toString());
        assertThat(savedEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(savedEvent.getCreatedAt()).isNotNull();
        assertThat(savedEvent.getProcessedAt()).isNull();
        assertThat(savedEvent.getAttempts()).isZero();
        assertThat(savedEvent.getLastError()).isNull();
    }

    @Test
    void insert_shouldUseDatabaseDefaultsForStatusAndAttempts() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                    """);
            statement.setObject(1, eventId);
            statement.setString(2, "Order");
            statement.setObject(3, aggregateId);
            statement.setString(4, "TestEvent");
            statement.setString(5, "{\"orderId\":\"" + aggregateId + "\"}");
            return statement;
        });

        Map<String, Object> defaults = jdbcTemplate.queryForMap(
                "SELECT status, attempts FROM outbox_events WHERE id = ?",
                eventId);

        assertThat(defaults)
                .containsEntry("status", OutboxEventStatus.PENDING.name())
                .containsEntry("attempts", 0);
    }

    @Test
    void findPendingBatchForUpdate_shouldReturnPendingEventsOrderedByCreatedAt() {
        UUID earliestPendingId = UUID.randomUUID();
        UUID processedId = UUID.randomUUID();
        UUID latestPendingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID middlePendingId = UUID.randomUUID();

        insertOutboxEvent(earliestPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
        insertOutboxEvent(processedId, OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:01:00Z"));
        insertOutboxEvent(latestPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:04:00Z"));
        insertOutboxEvent(failedId, OutboxEventStatus.FAILED, Instant.parse("2026-01-01T10:02:00Z"));
        insertOutboxEvent(middlePendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:03:00Z"));

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingBatchForUpdate(10);

        assertThat(pendingEvents)
                .extracting(OutboxEvent::getId)
                .containsExactly(earliestPendingId, middlePendingId, latestPendingId)
                .doesNotContain(processedId, failedId);
        assertThat(pendingEvents)
                .extracting(OutboxEvent::getStatus)
                .containsOnly(OutboxEventStatus.PENDING);
    }

    @Test
    void findPendingBatchForUpdate_shouldRespectBatchSize() {
        UUID firstPendingId = UUID.randomUUID();
        UUID secondPendingId = UUID.randomUUID();

        insertOutboxEvent(firstPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
        insertOutboxEvent(secondPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:01:00Z"));

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingBatchForUpdate(1);

        assertThat(pendingEvents)
                .extracting(OutboxEvent::getId)
                .containsExactly(firstPendingId);
    }

    @Test
    void summaryQueries_shouldReturnCountsAndOperationalTimestamps() {
        Instant oldestPendingCreatedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant newestFailedCreatedAt = Instant.parse("2026-01-01T10:04:00Z");

        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, oldestPendingCreatedAt);
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:03:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:01:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:02:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.FAILED, Instant.parse("2026-01-01T09:59:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.FAILED, newestFailedCreatedAt);

        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).isEqualTo(2L);
        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).isEqualTo(2L);
        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).isEqualTo(2L);
        assertThat(outboxEventRepository.count()).isEqualTo(6L);
        assertThat(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING))
                .hasValueSatisfying(actual -> assertThat(actual).isEqualTo(oldestPendingCreatedAt));
        assertThat(outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED))
                .hasValueSatisfying(actual -> assertThat(actual).isEqualTo(newestFailedCreatedAt));
    }

    @Test
    void insert_shouldRejectMissingRequiredFields() {
        for (String requiredColumn : List.of(
                "id",
                "aggregate_type",
                "aggregate_id",
                "event_type",
                "payload",
                "status",
                "created_at",
                "attempts")) {
            assertThatThrownBy(() -> jdbcTemplate.update(insertSqlWithNullValueFor(requiredColumn)))
                    .as("Expected database to reject null %s", requiredColumn)
                    .hasMessageContaining(requiredColumn);
        }
    }

    private void insertOutboxEvent(UUID eventId, OutboxEventStatus status, Instant createdAt) {
        UUID aggregateId = UUID.randomUUID();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (
                        id, aggregate_type, aggregate_id, event_type, payload, status, created_at, attempts
                    ) VALUES (
                        ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS timestamptz), ?
                    )
                    """);
            statement.setObject(1, eventId);
            statement.setString(2, "Order");
            statement.setObject(3, aggregateId);
            statement.setString(4, "TestEvent");
            statement.setString(5, "{\"orderId\":\"" + aggregateId + "\"}");
            statement.setString(6, status.name());
            statement.setString(7, createdAt.toString());
            statement.setInt(8, status == OutboxEventStatus.FAILED ? 1 : 0);
            return statement;
        });
    }

    private String insertSqlWithNullValueFor(String columnName) {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        return """
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload, status, created_at, attempts
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s
                )
                """.formatted(
                valueOrNull(columnName, "id", "'" + eventId + "'"),
                valueOrNull(columnName, "aggregate_type", "'Order'"),
                valueOrNull(columnName, "aggregate_id", "'" + aggregateId + "'"),
                valueOrNull(columnName, "event_type", "'TestEvent'"),
                valueOrNull(columnName, "payload", "'{\"orderId\":\"" + aggregateId + "\"}'::jsonb"),
                valueOrNull(columnName, "status", "'" + OutboxEventStatus.PENDING.name() + "'"),
                valueOrNull(columnName, "created_at", "CURRENT_TIMESTAMP"),
                valueOrNull(columnName, "attempts", "0"));
    }

    private String valueOrNull(String nullableColumnName, String currentColumnName, String value) {
        if (currentColumnName.equals(nullableColumnName)) {
            return "NULL";
        }
        return value;
    }
}
