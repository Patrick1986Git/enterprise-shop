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
        List<String> userIndexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND tablename = 'users'",
                String.class);

        assertThat(userIndexes)
                .isNotEmpty()
                .anyMatch(indexDef -> {
                    String normalized = indexDef == null
                            ? ""
                            : indexDef.toLowerCase().replace(" ", "");
                    return normalized.contains("unique")
                            && normalized.contains("lower(")
                            && normalized.contains("email");
                });
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

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    static class TestConfig {
    }
}