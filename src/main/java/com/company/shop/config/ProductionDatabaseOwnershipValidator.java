package com.company.shop.config;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionDatabaseOwnershipValidator implements ApplicationRunner {

    private static final List<String> PROTECTED_TABLES = List.of(
            "notification_admin_action_logs",
            "outbox_event_admin_action_logs",
            "reservation_expiration_admin_action_logs");

    private final JdbcTemplate jdbcTemplate;

    public ProductionDatabaseOwnershipValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> runtimeOwnedTables = jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema()
                  AND c.relname IN (?, ?, ?)
                  AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                ORDER BY c.relname
                """, String.class, PROTECTED_TABLES.toArray());

        if (!runtimeOwnedTables.isEmpty()) {
            throw new IllegalStateException(
                    "Production runtime database identity must not own protected admin action-log tables: "
                            + String.join(", ", runtimeOwnedTables));
        }
    }
}
