package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.company.shop.config.ProductionDatabaseOwnershipValidator;
import com.company.shop.config.ProductionFlywayIdentityConfiguration;

class LegacyProductionDatabaseOwnershipUpgradeIT {

    private static final String ADMIN_USER = "postgres";
    private static final String ADMIN_PASSWORD = "test_admin_password";
    private static final String LEGACY_RUNTIME_USER = "legacy_runtime";
    private static final String LEGACY_RUNTIME_PASSWORD = "test_legacy_runtime_password";
    private static final String MIGRATION_USER = "shop_migration";
    private static final String MIGRATION_PASSWORD = "test_migration_password";
    private static final String DATABASE_NAME = "enterprise_shop_legacy_test";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(ADMIN_USER)
            .withPassword(ADMIN_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/postgres/tsearch_data/polish.dict"),
                    "/usr/local/share/postgresql/tsearch_data/polish.dict")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/postgres/tsearch_data/polish.affix"),
                    "/usr/local/share/postgresql/tsearch_data/polish.affix")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/postgres/tsearch_data/polish.stop"),
                    "/usr/local/share/postgresql/tsearch_data/polish.stop")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("scripts/transfer-prod-db-ownership.sh", 0755),
                    "/test/transfer-prod-db-ownership.sh");

    @Test
    void legacyProductionUpgrade_shouldFailClosedUntilPrivilegedOwnershipTransferCompletes() throws Exception {
        POSTGRES.start();
        provisionLegacyRoles();

        Flyway legacyFlyway = flyway(LEGACY_RUNTIME_USER, LEGACY_RUNTIME_PASSWORD);
        assertThat(legacyFlyway.migrate().migrationsExecuted).isPositive();
        assertThat(legacyFlyway.info().pending()).isEmpty();
        assertLegacyOwnershipAndOwnerBypass();

        grantMigrationHistoryReadAccess();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(this::startProductionContext)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage()).isEqualTo(
                        "Production runtime database identity must not own protected admin action-log tables: "
                                + "notification_admin_action_logs, outbox_event_admin_action_logs, "
                                + "reservation_expiration_admin_action_logs"));

        executePrivilegedOwnershipTransfer();
        assertTransferredOwnershipAndRuntimePrivileges();

        try (ConfigurableApplicationContext context = startProductionContext()) {
            Flyway separatedFlyway = context.getBean(Flyway.class);
            assertThat(separatedFlyway.info().pending()).isEmpty();
            separatedFlyway.validate();
            assertThat(separatedFlyway.migrate().migrationsExecuted).isZero();
        }

        assertRuntimeProtectionAndOrdinaryDml();
    }

    private void assertLegacyOwnershipAndOwnerBypass() {
        JdbcTemplate runtime = jdbc(LEGACY_RUNTIME_USER, LEGACY_RUNTIME_PASSWORD);
        for (String table : managedTables()) {
            assertThat(tableOwner(runtime, table)).isEqualTo(LEGACY_RUNTIME_USER);
        }

        UUID id = insertNotificationLog(runtime);
        assertThat(runtime.update(
                "UPDATE notification_admin_action_logs SET actor_email = 'legacy-owner@example.com' WHERE id = ?", id))
                .isOne();
        assertThat(runtime.update("DELETE FROM notification_admin_action_logs WHERE id = ?", id)).isOne();
    }

    private void assertTransferredOwnershipAndRuntimePrivileges() {
        JdbcTemplate runtime = jdbc(LEGACY_RUNTIME_USER, LEGACY_RUNTIME_PASSWORD);
        for (String table : managedTables()) {
            assertThat(tableOwner(runtime, table)).isEqualTo(MIGRATION_USER);
        }

        Map<String, Object> attributes = runtime.queryForMap("""
                SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication
                FROM pg_roles WHERE rolname = current_user
                """);
        assertThat(attributes.values()).containsOnly(false);
        assertThat(runtime.queryForObject("""
                SELECT current_user <> pg_get_userbyid(datdba)
                FROM pg_database WHERE datname = current_database()
                """, Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("""
                SELECT current_user <> pg_get_userbyid(nspowner)
                FROM pg_namespace WHERE nspname = current_schema()
                """, Boolean.class)).isTrue();
    }

    private void assertRuntimeProtectionAndOrdinaryDml() {
        JdbcTemplate runtime = jdbc(LEGACY_RUNTIME_USER, LEGACY_RUNTIME_PASSWORD);
        JdbcTemplate owner = jdbc(MIGRATION_USER, MIGRATION_PASSWORD);
        UUID id = insertNotificationLog(runtime);

        assertThat(runtime.queryForObject(
                "SELECT count(*) FROM notification_admin_action_logs WHERE id = ?", Integer.class, id)).isOne();
        assertSqlState42501(() -> runtime.update(
                "UPDATE notification_admin_action_logs SET actor_email = 'blocked@example.com' WHERE id = ?", id));
        assertSqlState42501(() -> runtime.update(
                "DELETE FROM notification_admin_action_logs WHERE id = ?", id));
        assertThat(owner.update(
                "UPDATE notification_admin_action_logs SET actor_email = 'maintenance@example.com' WHERE id = ?", id))
                .isOne();
        assertThat(owner.update("DELETE FROM notification_admin_action_logs WHERE id = ?", id)).isOne();

        UUID categoryId = runtime.queryForObject(
                "INSERT INTO categories (name, slug) VALUES ('Legacy upgrade test', 'legacy-upgrade-test') RETURNING id",
                UUID.class);
        assertThat(runtime.update("UPDATE categories SET description = 'updated' WHERE id = ?", categoryId)).isOne();
        assertThat(runtime.update("DELETE FROM categories WHERE id = ?", categoryId)).isOne();
    }

    private ConfigurableApplicationContext startProductionContext() {
        return new SpringApplicationBuilder(TestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(Map.of(
                        "DATABASE_URL", POSTGRES.getJdbcUrl(),
                        "DATABASE_USERNAME", LEGACY_RUNTIME_USER,
                        "DATABASE_PASSWORD", LEGACY_RUNTIME_PASSWORD,
                        "FLYWAY_URL", POSTGRES.getJdbcUrl(),
                        "FLYWAY_USER", MIGRATION_USER,
                        "FLYWAY_PASSWORD", MIGRATION_PASSWORD))
                .run();
    }

    private void provisionLegacyRoles() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + LEGACY_RUNTIME_USER
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '"
                    + LEGACY_RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + MIGRATION_USER
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '"
                    + MIGRATION_PASSWORD + "'");
            statement.execute("ALTER DATABASE " + DATABASE_NAME + " OWNER TO " + LEGACY_RUNTIME_USER);
            statement.execute("ALTER SCHEMA public OWNER TO " + LEGACY_RUNTIME_USER);
        }
    }

    private void grantMigrationHistoryReadAccess() {
        JdbcTemplate admin = jdbc(ADMIN_USER, ADMIN_PASSWORD);
        admin.execute("GRANT USAGE ON SCHEMA public TO " + MIGRATION_USER);
        admin.execute("GRANT SELECT ON flyway_schema_history TO " + MIGRATION_USER);
    }

    private void executePrivilegedOwnershipTransfer() throws Exception {
        String command = "PGPASSWORD='" + ADMIN_PASSWORD + "' "
                + "DATABASE_ADMIN_URL='postgresql://127.0.0.1:5432/" + DATABASE_NAME + "' "
                + "DATABASE_NAME='" + DATABASE_NAME + "' DATABASE_ADMIN_USER='" + ADMIN_USER + "' "
                + "LEGACY_RUNTIME_USER='" + LEGACY_RUNTIME_USER + "' FLYWAY_USER='" + MIGRATION_USER + "' "
                + "/test/transfer-prod-db-ownership.sh";
        var result = POSTGRES.execInContainer("sh", "-c", command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private Flyway flyway(String user, String password) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), user, password)
                .baselineOnMigrate(false)
                .locations("classpath:db/migration")
                .load();
    }

    private JdbcTemplate jdbc(String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password));
    }

    private UUID insertNotificationLog(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO notification_admin_action_logs
                    (notification_id, action_type, actor_email, details)
                VALUES (?, 'REQUEUE', 'legacy-upgrade@example.com', 'test')
                RETURNING id
                """, UUID.class, UUID.randomUUID());
    }

    private String tableOwner(JdbcTemplate jdbcTemplate, String table) {
        return jdbcTemplate.queryForObject("""
                SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = ?::regclass
                """, String.class, table);
    }

    private String[] managedTables() {
        return new String[] {
                "orders",
                "notification_admin_action_logs",
                "outbox_event_admin_action_logs",
                "reservation_expiration_admin_action_logs"
        };
    }

    private void assertSqlState42501(Runnable operation) {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(operation::run)
                .satisfies(exception -> assertThat(findSqlState(exception)).isEqualTo("42501"));
    }

    private String findSqlState(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.company.shop")
    @Import({
            ProductionFlywayIdentityConfiguration.class,
            ProductionDatabaseOwnershipValidator.class
    })
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    static class TestConfig {
    }
}
