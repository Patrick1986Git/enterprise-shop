package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
