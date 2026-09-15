package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
        classes = PostgresSchemaArtifactsIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class PostgresSchemaArtifactsIT extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schema_shouldRequireAndIndexDeterministicProductImageOrder() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT is_nullable = 'NO'
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'product_images'
                  AND column_name = 'sort_order'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'product_images'
                """, String.class)).contains("idx_product_images_product_order");
    }

    @Test
    void schema_shouldContainCriticalFlywayAndDomainTables() {
        Boolean flywayHistoryExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history')",
                Boolean.class);

        Boolean productReviewsExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name = 'product_reviews')",
                Boolean.class);

        assertThat(flywayHistoryExists).isTrue();
        assertThat(productReviewsExists).isTrue();
    }

    @Test
    void schema_shouldContainCaseInsensitiveEmailUniquenessMechanism() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint constraint_definition
                    JOIN pg_class users_table ON users_table.oid = constraint_definition.conrelid
                    JOIN pg_namespace table_schema ON table_schema.oid = users_table.relnamespace
                    JOIN pg_attribute email_column
                      ON email_column.attrelid = users_table.oid
                     AND email_column.attname = 'email'
                    WHERE table_schema.nspname = 'public'
                      AND users_table.relname = 'users'
                      AND constraint_definition.conname = 'users_email_key'
                      AND constraint_definition.contype = 'u'
                      AND array_length(constraint_definition.conkey, 1) = 1
                      AND email_column.attnum = ANY (constraint_definition.conkey)
                )
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_index index_definition
                    JOIN pg_class index_relation ON index_relation.oid = index_definition.indexrelid
                    JOIN pg_class users_table ON users_table.oid = index_definition.indrelid
                    JOIN pg_namespace table_schema ON table_schema.oid = users_table.relnamespace
                    WHERE table_schema.nspname = 'public'
                      AND users_table.relname = 'users'
                      AND index_relation.relname = 'ux_users_email_lower'
                      AND index_definition.indisunique
                      AND index_definition.indpred IS NULL
                      AND lower(pg_get_expr(index_definition.indexprs, index_definition.indrelid))
                            LIKE 'lower(%email%)'
                )
                """, Boolean.class)).isTrue();
    }

    @Test
    void schema_shouldContainFtsArtifactsForProductsSearch() {
        Boolean searchVectorColumnExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = 'products' AND column_name = 'search_vector')",
                Boolean.class);

        Boolean ginIndexForSearchVectorExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND tablename = 'products' " +
                        "AND indexname = 'idx_products_search_vector' " +
                        "AND indexdef ILIKE '%USING gin%' " +
                        "AND indexdef ILIKE '%search_vector%')",
                Boolean.class);

        Boolean polishDictionaryExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_ts_dict WHERE dictname = 'polish_hunspell')",
                Boolean.class);

        Boolean polishConfigExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'polish')",
                Boolean.class);

        assertThat(searchVectorColumnExists).isTrue();
        assertThat(ginIndexForSearchVectorExists).isTrue();
        assertThat(polishDictionaryExists).isTrue();
        assertThat(polishConfigExists).isTrue();
    }

    @Test
    void schema_shouldProtectEveryAdminActionLogWithAppendOnlyTrigger() {
        List<String> protectedTables = jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_proc p ON p.oid = t.tgfoid
                WHERE NOT t.tgisinternal
                  AND p.proname = 'reject_runtime_admin_action_log_mutation'
                ORDER BY c.relname
                """, String.class);

        assertThat(protectedTables).containsExactly(
                "notification_admin_action_logs",
                "outbox_event_admin_action_logs",
                "reservation_expiration_admin_action_logs",
                "stripe_payment_conflict_dispositions");
    }

    @Test
    void schema_shouldContainImmutableStripePaymentConflictEvidence() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'stripe_payment_conflicts')
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_proc p ON p.oid = t.tgfoid
                    WHERE NOT t.tgisinternal AND c.relname = 'stripe_payment_conflicts'
                      AND p.proname = 'reject_runtime_stripe_payment_conflict_mutation')
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'stripe_payment_conflicts'
                """, String.class)).contains("uq_stripe_payment_conflicts_event",
                        "idx_stripe_payment_conflicts_observed_id");
    }

    @Test
    void schema_shouldContainIndexedConflictDispositionHistoryWithNonCascadingForeignKey() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'stripe_payment_conflict_dispositions'
                """, String.class)).contains("idx_stripe_conflict_dispositions_conflict_created");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT confdeltype = 'a'
                FROM pg_constraint
                WHERE conname = 'fk_stripe_conflict_dispositions_conflict'
                """, Boolean.class)).isTrue();
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
