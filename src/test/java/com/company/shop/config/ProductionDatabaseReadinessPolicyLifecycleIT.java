package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;

@SpringBootTest(
        classes = ProductionDatabaseReadinessPolicyLifecycleIT.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "spring.data.jpa.repositories.enabled=false",
                "management.endpoint.health.show-details=when_authorized",
                "server.tomcat.threads.max=8",
                "server.tomcat.max-connections=32",
                "server.tomcat.accept-count=8",
                "server.tomcat.connection-timeout=5s",
                "security.jwt.key-id=readiness-test-key",
                "security.jwt.secret=cmVhZGluZXNzLXRlc3Qtc2VjcmV0LXdpdGgtMzItYnl0ZXM=",
                "stripe.api-key=readiness-test-stripe-key",
                "stripe.webhook-secret=readiness-test-webhook-secret",
                "stripe.public-key=readiness-test-public-key"
        }
)
@ActiveProfiles("prod")
class ProductionDatabaseReadinessPolicyLifecycleIT {

    private static final String DATABASE = "readiness_policy_lifecycle";
    private static final String RUNTIME_USER = "readiness_runtime";
    private static final String RUNTIME_PASSWORD = "readiness_runtime_password";
    private static final String MIGRATION_USER = "readiness_migration";
    private static final String MIGRATION_PASSWORD = "readiness_migration_password";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName(DATABASE)
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
        try {
            provisionIdentitiesAndRuntimePolicy();
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    static void provisionIdentitiesAndRuntimePolicy() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + MIGRATION_USER + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'");
            restoreSafePolicy(statement);
        }
    }

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 500);
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> 17);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATION_USER);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    @Test
    void readiness_shouldTrackSafePoolCapacityAcrossRuntimePolicyDriftAndRepair() throws Exception {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);

        HttpResponse<String> initialReadiness = get("/actuator/health/readiness");
        assertHealth(initialReadiness, HttpStatus.OK, "UP");
        assertHealth(get("/actuator/health/liveness"), HttpStatus.OK, "UP");

        int originalBackendPid;
        try (Connection existingSafeSession = dataSource.getConnection()) {
            originalBackendPid = backendPid(existingSafeSession);
            assertThat(settings(existingSafeSession)).containsEntry("statement_timeout", "9s");

            setStatementTimeout("0");

            assertThat(backendPid(existingSafeSession)).isEqualTo(originalBackendPid);
            assertThat(settings(existingSafeSession)).containsEntry("statement_timeout", "9s");
        }

        assertHealth(get("/actuator/health/readiness"), HttpStatus.OK, "UP");

        dataSource.getHikariPoolMXBean().softEvictConnections();
        await(() -> dataSource.getHikariPoolMXBean().getTotalConnections() == 0);

        HttpResponse<String> unavailableReadiness = get("/actuator/health/readiness");
        assertHealth(unavailableReadiness, HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
        assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isZero();
        assertHealth(get("/actuator/health/liveness"), HttpStatus.OK, "UP");

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            restoreSafePolicy(statement);
        }

        await(() -> get("/actuator/health/readiness").statusCode() == HttpStatus.OK.value());
        assertHealth(get("/actuator/health/readiness"), HttpStatus.OK, "UP");

        try (Connection recoveredSession = dataSource.getConnection()) {
            assertThat(backendPid(recoveredSession)).isNotEqualTo(originalBackendPid);
            assertThat(settings(recoveredSession))
                    .containsEntry("statement_timeout", "9s")
                    .containsEntry("lock_timeout", "3s")
                    .containsEntry("idle_timeout", "7s");
            assertThat(recoveredSession.getNetworkTimeout()).isEqualTo(17_000);
        }
    }

    private HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Health endpoint request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Health endpoint request was interrupted", exception);
        }
    }

    private void assertHealth(HttpResponse<String> response, HttpStatus status, String health) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status.value());
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() { });
        assertThat(body).containsOnlyKeys("status").containsEntry("status", health);
        assertThat(response.body()).doesNotContain(
                RUNTIME_USER, RUNTIME_PASSWORD, MIGRATION_USER, MIGRATION_PASSWORD,
                POSTGRES.getJdbcUrl(), "connectionInitSql", "statement_timeout", "SQLException");
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static Map<String, Object> settings(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT current_setting('statement_timeout') AS statement_timeout,
                               current_setting('lock_timeout') AS lock_timeout,
                               current_setting('idle_in_transaction_session_timeout') AS idle_timeout
                        """)) {
            result.next();
            return Map.of(
                    "statement_timeout", result.getString("statement_timeout"),
                    "lock_timeout", result.getString("lock_timeout"),
                    "idle_timeout", result.getString("idle_timeout"));
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            SecurityConfig.class,
            JwtAuthenticationFilter.class,
            ProductionDatabaseWaitPolicyValidator.class
    })
    static class TestApplication {
    }
}
