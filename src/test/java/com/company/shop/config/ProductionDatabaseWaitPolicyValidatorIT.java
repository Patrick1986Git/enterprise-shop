package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ProductionDatabaseWaitPolicyValidatorIT {

    private static final String DATABASE = "wait_policy_test";
    private static final String RUNTIME_USER = "wait_runtime";
    private static final String MIGRATION_USER = "wait_migration";
    private static final String PASSWORD = "wait_policy_password";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName(DATABASE)
            .withUsername("postgres")
            .withPassword("postgres");

    @BeforeAll
    static void provisionIdentitiesAndPolicy() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("CREATE ROLE " + MIGRATION_USER + " LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("ALTER ROLE " + RUNTIME_USER + " SET statement_timeout = '9s'");
            statement.execute("ALTER ROLE " + RUNTIME_USER + " SET lock_timeout = '3s'");
            statement.execute("ALTER ROLE " + RUNTIME_USER
                    + " IN DATABASE " + DATABASE + " SET idle_in_transaction_session_timeout = '7s'");
        }
    }

    @Test
    void roleAndRoleInDatabaseDefaults_shouldApplyToDistinctAndReplacementRuntimeSessions() throws SQLException {
        try (Connection first = runtimeConnection(); Connection distinct = runtimeConnection()) {
            assertFiniteRuntimePolicy(first);
            assertFiniteRuntimePolicy(distinct);
        }

        try (Connection replacement = runtimeConnection()) {
            assertFiniteRuntimePolicy(replacement);
        }
    }

    @Test
    void validator_shouldObserveEffectiveRuntimePolicyWithoutConstrainingMigrationIdentity() {
        assertThatCode(() -> validator(RUNTIME_USER).run(null)).doesNotThrowAnyException();

        assertThat(settings(MIGRATION_USER)).containsEntry("statement_timeout", "0")
                .containsEntry("lock_timeout", "0")
                .containsEntry("idle_in_transaction_session_timeout", "0");
    }

    @Test
    void validator_shouldIndependentlyRejectEachDisabledEffectiveRuntimeSetting() throws SQLException {
        assertRejectedAfterSet("statement_timeout", "0", "statement_timeout");
        assertRejectedAfterSet("lock_timeout", "0", "lock_timeout");
        assertRejectedAfterSet("idle_in_transaction_session_timeout", "0", "idle_in_transaction_session_timeout");
    }

    @Test
    void validator_shouldRejectLockTimeoutThatIsNotShorterThanStatementTimeout() throws SQLException {
        assertRejectedAfterSet("lock_timeout", "9s", "lock_timeout must be shorter than statement_timeout");
    }

    private static void assertFiniteRuntimePolicy(Connection connection) {
        JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                connection, true));
        assertThat(jdbc.queryForMap("""
                SELECT current_setting('statement_timeout') AS statement_timeout,
                       current_setting('lock_timeout') AS lock_timeout,
                       current_setting('idle_in_transaction_session_timeout') AS idle_timeout
                """))
                .containsEntry("statement_timeout", "9s")
                .containsEntry("lock_timeout", "3s")
                .containsEntry("idle_timeout", "7s");
    }

    private static void assertRejectedAfterSet(String setting, String value, String message) throws SQLException {
        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET " + setting + " = '" + value + "'");
            assertThatIllegalStateException()
                    .isThrownBy(() -> validator(connection).run(null))
                    .withMessageContaining(message);
        }
    }

    private static Map<String, Object> settings(String username) {
        return jdbc(username).queryForMap("""
                SELECT current_setting('statement_timeout') AS statement_timeout,
                       current_setting('lock_timeout') AS lock_timeout,
                       current_setting('idle_in_transaction_session_timeout') AS idle_in_transaction_session_timeout
                """);
    }

    private static ProductionDatabaseWaitPolicyValidator validator(String username) {
        return new ProductionDatabaseWaitPolicyValidator(jdbc(username));
    }

    private static ProductionDatabaseWaitPolicyValidator validator(Connection connection) {
        return new ProductionDatabaseWaitPolicyValidator(new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)));
    }

    private static JdbcTemplate jdbc(String username) {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), username, PASSWORD));
    }

    private static Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USER, PASSWORD);
    }
}
