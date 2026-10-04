package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
class ProductionDatabaseWaitPolicyConnectionLifecycleIT {

    private static final String DATABASE = "wait_policy_lifecycle";
    private static final String RUNTIME_USER = "wait_lifecycle_runtime";
    private static final String PASSWORD = "wait_lifecycle_password";
    private static final String CONNECTION_POLICY_CHECK = """
            DO $wait_policy$
            BEGIN
              IF NOT (
                current_setting('statement_timeout')::interval > interval '0'
                AND current_setting('lock_timeout')::interval > interval '0'
                AND current_setting('lock_timeout')::interval < current_setting('statement_timeout')::interval
                AND current_setting('idle_in_transaction_session_timeout')::interval > interval '0'
              ) THEN
                RAISE EXCEPTION 'Production runtime database wait policy is unsafe' USING ERRCODE = '22023';
              END IF;
            END
            $wait_policy$
            """;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName(DATABASE)
            .withUsername("postgres")
            .withPassword("postgres");

    @BeforeAll
    static void provisionRuntimePolicy() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + PASSWORD + "'");
            restoreSafePolicy(statement);
        }
    }

    @AfterEach
    void restorePolicy() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            restoreSafePolicy(statement);
        }
    }

    @Test
    void replacementConnection_withoutCreationValidation_canMakePoolPolicyHeterogeneous() throws SQLException {
        try (HikariDataSource dataSource = dataSource(null); Connection existingSafeSession = dataSource.getConnection()) {
            Map<String, Object> safe = settings(existingSafeSession);
            assertThat(safe).containsEntry("statement_timeout", "9s");

            setStatementTimeout("0");

            try (Connection unsafeReplacement = dataSource.getConnection()) {
                assertThat(settings(existingSafeSession))
                        .containsEntry("backend_pid", safe.get("backend_pid"))
                        .containsEntry("statement_timeout", "9s");
                assertThat(settings(unsafeReplacement))
                        .doesNotContainEntry("backend_pid", safe.get("backend_pid"))
                        .containsEntry("statement_timeout", "0");
            }
        }
    }

    @Test
    void replacementConnection_withCreationValidation_isRejectedUntilPolicyIsRepaired() throws SQLException {
        try (HikariDataSource dataSource = dataSource(CONNECTION_POLICY_CHECK);
                Connection existingSafeSession = dataSource.getConnection()) {
            int safeBackendPid = backendPid(existingSafeSession);
            assertThat(existingSafeSession.getNetworkTimeout()).isEqualTo(17_000);

            setStatementTimeout("0");

            assertThatThrownBy(dataSource::getConnection)
                    .isInstanceOf(SQLTransientConnectionException.class)
                    .hasMessageContaining("Connection is not available");
            assertThat(backendPid(existingSafeSession)).isEqualTo(safeBackendPid);
            assertThat(settings(existingSafeSession)).containsEntry("statement_timeout", "9s");

            setStatementTimeout("9s");

            try (Connection repairedReplacement = dataSource.getConnection()) {
                assertThat(backendPid(repairedReplacement)).isNotEqualTo(safeBackendPid);
                assertThat(settings(repairedReplacement)).containsEntry("statement_timeout", "9s");
                assertThat(repairedReplacement.getNetworkTimeout()).isEqualTo(17_000);
            }
        }
    }

    private static HikariDataSource dataSource(String connectionInitSql) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(RUNTIME_USER);
        config.setPassword(PASSWORD);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(500);
        config.setInitializationFailTimeout(5_000);
        config.addDataSourceProperty("socketTimeout", "17");
        if (connectionInitSql != null) {
            config.setConnectionInitSql(connectionInitSql);
        }
        return new HikariDataSource(config);
    }

    private static Map<String, Object> settings(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT pg_backend_pid() AS backend_pid,
                               current_setting('statement_timeout') AS statement_timeout,
                               current_setting('lock_timeout') AS lock_timeout,
                               current_setting('idle_in_transaction_session_timeout') AS idle_timeout
                        """)) {
            result.next();
            return Map.of(
                    "backend_pid", result.getInt("backend_pid"),
                    "statement_timeout", result.getString("statement_timeout"),
                    "lock_timeout", result.getString("lock_timeout"),
                    "idle_timeout", result.getString("idle_timeout"));
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        return (int) settings(connection).get("backend_pid");
    }

    private static void setStatementTimeout(String value) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE " + RUNTIME_USER + " SET statement_timeout = '" + value + "'");
        }
    }

    private static void restoreSafePolicy(Statement statement) throws SQLException {
        statement.execute("ALTER ROLE " + RUNTIME_USER + " SET statement_timeout = '9s'");
        statement.execute("ALTER ROLE " + RUNTIME_USER + " SET lock_timeout = '3s'");
        statement.execute("ALTER ROLE " + RUNTIME_USER + " IN DATABASE " + DATABASE
                + " SET idle_in_transaction_session_timeout = '7s'");
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
