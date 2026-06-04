package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
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
        classes = FlywayMigrationSmokeIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class FlywayMigrationSmokeIT extends PostgresContainerSupport {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrate_shouldApplyAllResolvedVersionedMigrationsOnCleanPostgres() {
        MigrationInfoService info = flyway.info();

        MigrationInfo current = info.current();
        assertThat(current).isNotNull();

        MigrationVersion highestResolvedVersion = Arrays.stream(info.all())
                .filter(migration -> migration.getVersion() != null)
                .filter(migration -> migration.getState().isResolved())
                .map(MigrationInfo::getVersion)
                .max(MigrationVersion::compareTo)
                .orElseThrow(() -> new IllegalStateException("No resolved versioned Flyway migrations found"));

        assertThat(current.getVersion()).isEqualTo(highestResolvedVersion);
        assertThat(info.pending()).isEmpty();
        assertThat(info.applied())
                .isNotEmpty()
                .allMatch(migration -> migration.getState().isApplied());
    }

    @Test
    void migrate_shouldContainSuccessfulRowsAndNoFailuresInFlywaySchemaHistory() {
        Integer failedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);

        Integer successfulRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);

        assertThat(failedRows).isZero();
        assertThat(successfulRows).isNotNull().isGreaterThan(0);
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