package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;

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
        classes = ReservationExpirationMigrationIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class ReservationExpirationMigrationIT extends PostgresContainerSupport {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migration_shouldCreateNullableDeadlineAndDurableWorkIndexes() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'orders' AND column_name = 'reservation_expires_at'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE indexname IN ('idx_orders_due_reservation_expiration',
                  'idx_reservation_expiration_work_due', 'idx_reservation_expiration_work_claim_lease')
                """, String.class)).containsExactlyInAnyOrder("idx_orders_due_reservation_expiration",
                        "idx_reservation_expiration_work_due", "idx_reservation_expiration_work_claim_lease");
    }

    @Test
    void legacyNewOrderWithNullDeadline_shouldHaveNoExpirationWork() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM reservation_expiration_work w
                JOIN orders o ON o.id = w.order_id WHERE o.reservation_expires_at IS NULL
                """, Integer.class);
        assertThat(count).isZero();
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
