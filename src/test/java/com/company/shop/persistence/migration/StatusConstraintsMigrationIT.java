package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
        classes = StatusConstraintsMigrationIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class StatusConstraintsMigrationIT extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrate_shouldCreateCheckConstraintsForOrderAndPaymentStatuses() {
        Map<String, String> constraints = jdbcTemplate.query(
                "SELECT con.conname, pg_get_constraintdef(con.oid) AS definition " +
                        "FROM pg_constraint con " +
                        "JOIN pg_namespace nsp ON nsp.oid = con.connamespace " +
                        "WHERE nsp.nspname = 'public' " +
                        "AND con.contype = 'c' " +
                        "AND con.conname IN (" +
                        "'chk_orders_status_allowed', " +
                        "'chk_payments_status_allowed', " +
                        "'chk_payments_payment_method_allowed')",
                rs -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("conname"), rs.getString("definition"));
                    }
                    return result;
                });

        assertThat(constraints)
                .hasSize(3)
                .containsKeys(
                        "chk_orders_status_allowed",
                        "chk_payments_status_allowed",
                        "chk_payments_payment_method_allowed");

        assertThat(constraints.get("chk_orders_status_allowed"))
                .contains("NEW")
                .contains("PAID")
                .contains("SHIPPED")
                .contains("CANCELLED");

        assertThat(constraints.get("chk_payments_status_allowed"))
                .contains("PENDING")
                .contains("COMPLETED")
                .contains("FAILED")
                .contains("REFUNDED");

        assertThat(constraints.get("chk_payments_payment_method_allowed"))
                .contains("payment_method")
                .contains("STRIPE");
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
