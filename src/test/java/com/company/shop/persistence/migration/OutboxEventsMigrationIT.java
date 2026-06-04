package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest(
        classes = OutboxEventsMigrationIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class OutboxEventsMigrationIT extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrate_shouldCreateOutboxEventsTableWithRequiredColumns() {
        Map<String, String> columns = jdbcTemplate.query(
                """
                        SELECT column_name, data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                        AND table_name = 'outbox_events'
                        AND column_name IN (
                            'id',
                            'aggregate_type',
                            'aggregate_id',
                            'event_type',
                            'payload',
                            'status',
                            'created_at',
                            'processed_at',
                            'attempts',
                            'last_error'
                        )
                        """,
                rs -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("column_name"), rs.getString("data_type"));
                    }
                    return result;
                });

        assertThat(columns)
                .containsEntry("id", "uuid")
                .containsEntry("aggregate_type", "character varying")
                .containsEntry("aggregate_id", "uuid")
                .containsEntry("event_type", "character varying")
                .containsEntry("payload", "jsonb")
                .containsEntry("status", "character varying")
                .containsEntry("created_at", "timestamp with time zone")
                .containsEntry("processed_at", "timestamp with time zone")
                .containsEntry("attempts", "integer")
                .containsEntry("last_error", "text");
    }

    @Test
    void migrate_shouldCreateOutboxEventsIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList(
                """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                        AND tablename = 'outbox_events'
                        """,
                String.class);

        assertThat(indexNames)
                .contains(
                        "outbox_events_pkey",
                        "idx_outbox_events_status_created_at",
                        "idx_outbox_events_aggregate");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    static class TestConfig {
    }
}
