package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.company.shop.config.ProductionFlywayIdentityConfiguration;

@SpringBootTest(
        classes = ProductionDatabaseIdentityIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("prod")
class ProductionDatabaseIdentityIT {

    private static final String ADMIN_USER = "postgres";
    private static final String ADMIN_PASSWORD = "test_admin_password";
    private static final String MIGRATION_USER = "shop_migration";
    private static final String MIGRATION_PASSWORD = "test_migration_password";
    private static final String RUNTIME_USER = "shop_runtime";
    private static final String RUNTIME_PASSWORD = "test_runtime_password";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("enterprise_shop_prod_test")
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
                    "/usr/local/share/postgresql/tsearch_data/polish.stop");

    static {
        POSTGRES.start();
        provisionProductionRoles();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATION_USER);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    @Test
    void prodStartup_shouldSeparateRuntimeAndMigrationIdentitiesAndValidateExistingSchema() throws SQLException {
        assertThat(currentUser(dataSource)).isEqualTo(RUNTIME_USER);
        assertThat(currentUser(flyway.getConfiguration().getDataSource())).isEqualTo(MIGRATION_USER);
        assertThat(flyway.info().pending()).isEmpty();

        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void freshMigration_shouldKeepRuntimeLeastPrivilegeAndMigrationOwnerControlled() {
        Map<String, Object> runtimeAttributes = jdbcTemplate.queryForMap("""
                SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication
                FROM pg_roles
                WHERE rolname = current_user
                """);

        assertThat(runtimeAttributes.values()).containsOnly(false);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_user = pg_get_userbyid(datdba)
                FROM pg_database
                WHERE datname = current_database()
                """, Boolean.class))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_user = pg_get_userbyid(nspowner)
                FROM pg_namespace
                WHERE nspname = 'public'
                """, Boolean.class))
                .isFalse();

        List<String> tables = List.of(
                "orders",
                "notification_admin_action_logs",
                "outbox_event_admin_action_logs",
                "reservation_expiration_admin_action_logs");
        for (String table : tables) {
            assertThat(tableOwner(table)).isEqualTo(MIGRATION_USER);
        }
    }

    @Test
    void v45_shouldAllowRuntimeAppendAndReadButRejectMutationWhileOwnerRetainsMaintenance() {
        UUID notificationLogId = insertLog("notification_admin_action_logs", "notification_id");
        UUID outboxLogId = insertLog("outbox_event_admin_action_logs", "outbox_event_id");
        UUID reservationLogId = jdbcTemplate.queryForObject("""
                INSERT INTO reservation_expiration_admin_action_logs
                    (order_id, work_id, action_type, outcome, actor_email, created_at)
                VALUES (?, ?, 'RECOVERY', 'TERMINAL_NOOP', 'identity-test@example.com', CURRENT_TIMESTAMP)
                RETURNING id
                """, UUID.class, UUID.randomUUID(), UUID.randomUUID());

        for (Map.Entry<String, UUID> row : Map.of(
                "notification_admin_action_logs", notificationLogId,
                "outbox_event_admin_action_logs", outboxLogId,
                "reservation_expiration_admin_action_logs", reservationLogId).entrySet()) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + row.getKey() + " WHERE id = ?", Integer.class, row.getValue()))
                    .isOne();
            assertRuntimeMutationDenied("UPDATE " + row.getKey()
                    + " SET actor_email = 'changed@example.com' WHERE id = '" + row.getValue() + "'");
            assertRuntimeMutationDenied("DELETE FROM " + row.getKey() + " WHERE id = '" + row.getValue() + "'");

            ownerJdbc().update("UPDATE " + row.getKey()
                    + " SET actor_email = 'owner-maintenance@example.com' WHERE id = ?", row.getValue());
            assertThat(ownerJdbc().update("DELETE FROM " + row.getKey() + " WHERE id = ?", row.getValue())).isOne();
        }
    }

    @Test
    void runtime_shouldRetainOrdinaryBusinessDml() {
        UUID categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name, slug) VALUES ('Prod identity test', 'prod-identity-test') RETURNING id",
                UUID.class);

        assertThat(jdbcTemplate.update("UPDATE categories SET description = 'updated' WHERE id = ?", categoryId)).isOne();
        assertThat(jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId)).isOne();
    }

    @Test
    void prodFlyway_shouldRejectNonEmptySchemaWithoutHistory() {
        ownerJdbc().execute("CREATE SCHEMA unmanaged_identity_test AUTHORIZATION " + MIGRATION_USER);
        ownerJdbc().execute("CREATE TABLE unmanaged_identity_test.existing_data (id BIGINT PRIMARY KEY)");

        Flyway unmanaged = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)
                .schemas("unmanaged_identity_test")
                .defaultSchema("unmanaged_identity_test")
                .baselineOnMigrate(false)
                .locations("classpath:db/migration")
                .load();

        assertThatExceptionOfType(FlywayException.class)
                .isThrownBy(unmanaged::migrate)
                .withMessageContaining("non-empty schema")
                .withMessageContaining("no schema history table");
    }

    private UUID insertLog(String table, String referenceColumn) {
        return jdbcTemplate.queryForObject("INSERT INTO " + table + " (" + referenceColumn
                + ", action_type, actor_email, details) VALUES (?, 'REQUEUE', 'identity-test@example.com', 'test') RETURNING id",
                UUID.class, UUID.randomUUID());
    }

    private void assertRuntimeMutationDenied(String sql) {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> jdbcTemplate.update(sql))
                .satisfies(exception -> assertThat(findSqlState(exception)).isEqualTo("42501"));
    }

    private String tableOwner(String table) {
        return jdbcTemplate.queryForObject("""
                SELECT pg_get_userbyid(relowner)
                FROM pg_class
                WHERE oid = ?::regclass
                """, String.class, table);
    }

    private static String currentUser(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT current_user")) {
            result.next();
            return result.getString(1);
        }
    }

    private static String findSqlState(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD));
    }

    private static void provisionProductionRoles() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATION_USER
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + MIGRATION_PASSWORD + "'");
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("ALTER DATABASE enterprise_shop_prod_test OWNER TO " + MIGRATION_USER);
            statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_USER);
            statement.execute("GRANT CONNECT ON DATABASE enterprise_shop_prod_test TO " + RUNTIME_USER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                    + " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + RUNTIME_USER);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                    + " IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + RUNTIME_USER);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                    + " IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO " + RUNTIME_USER);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to provision isolated production database roles", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.company.shop")
    @Import(ProductionFlywayIdentityConfiguration.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    static class TestConfig {
    }
}
