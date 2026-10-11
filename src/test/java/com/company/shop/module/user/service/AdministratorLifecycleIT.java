package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.module.user.exception.UserAdministratorContinuityException;
import com.company.shop.security.AuthService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdministratorLifecycleIT {

    private static final String DATABASE = "administrator_lifecycle_test";
    private static final String MIGRATION = "lifecycle_migration";
    private static final String RUNTIME = "lifecycle_runtime";
    private static final String OPERATOR = "lifecycle_operator";
    private static final String DB_PASSWORD = "synthetic_database_password";
    private static final String USER_PASSWORD = "StrongPassword123!";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName(DATABASE).withUsername("postgres").withPassword(DB_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.dict"), "/usr/local/share/postgresql/tsearch_data/polish.dict")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.affix"), "/usr/local/share/postgresql/tsearch_data/polish.affix")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.stop"), "/usr/local/share/postgresql/tsearch_data/polish.stop");

    static {
        POSTGRES.start();
        try (Connection connection = connection("postgres"); Statement sql = connection.createStatement()) {
            for (String role : new String[]{MIGRATION, RUNTIME, OPERATOR}) {
                sql.execute("CREATE ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + DB_PASSWORD + "'");
            }
            sql.execute("ALTER DATABASE " + DATABASE + " OWNER TO " + MIGRATION);
            sql.execute("ALTER SCHEMA public OWNER TO " + MIGRATION);
            sql.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + RUNTIME);
            sql.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            sql.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION + " IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + RUNTIME);
            sql.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION + " IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO " + RUNTIME);
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate runtime;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> RUNTIME);
        properties.add("spring.datasource.password", () -> DB_PASSWORD);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", () -> MIGRATION);
        properties.add("spring.flyway.password", () -> DB_PASSWORD);
    }

    @BeforeEach
    void prepare() throws Exception {
        owner().execute("TRUNCATE public.users CASCADE");
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/configure-local-admin-operator.sql"), "/tmp/configure-operator.sql");
        var result = POSTGRES.execInContainer("psql", "-X", "-U", "postgres", "-d", DATABASE,
                "--set=operator_login=" + OPERATOR, "--set=runtime_login=" + RUNTIME,
                "--set=migration_login=" + MIGRATION, "--set=expected_database=" + DATABASE,
                "--file=/tmp/configure-operator.sql");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    @Test
    void freshRegistration_shouldRemainZeroAdministratorAndAssignOnlyRoleUser() {
        UUID id = registered();
        assertThat(usableAdministrators()).isZero();
        assertThat(roles(id)).containsExactly("ROLE_USER");
        assertThat(version(id)).isZero();
    }

    @Test
    void promotion_shouldCommitMembershipVersionAndAuthenticEvidenceAndInvalidateJwt() throws Exception {
        UUID id = registered();
        String email = runtime.queryForObject("SELECT email FROM users WHERE id=?", String.class, id);
        String oldToken = login(email);
        owner().update("INSERT INTO roles(name) VALUES ('ROLE_EXTRA') ON CONFLICT(name) DO NOTHING");
        owner().update("INSERT INTO user_roles SELECT ?, id FROM roles WHERE name='ROLE_EXTRA'", id);
        UUID evidence;
        try (Connection operator = connection(OPERATOR)) { evidence = promote(operator, id, DATABASE, "local"); }
        assertThat(roles(id)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN", "ROLE_EXTRA");
        assertThat(version(id)).isOne();
        assertThat(owner().queryForMap("SELECT operator_login::text, operation, credential_version_before, credential_version_after FROM administrator_lifecycle.promotion_evidence WHERE id=?", evidence))
                .containsEntry("operator_login", OPERATOR).containsEntry("operation", "ZERO_ADMIN_PROMOTION")
                .containsEntry("credential_version_before", 0L).containsEntry("credential_version_after", 1L);
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + oldToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + login(email))).andExpect(status().isOk());
    }

    @Test
    void promotion_shouldRejectExistingAdministratorAndRepeatedAttemptWithoutAdditionalEvidence() throws Exception {
        UUID first = registered();
        UUID second = registered();
        try (Connection operator = connection(OPERATOR)) {
            promote(operator, first, DATABASE, "local");
            assertRejected(() -> promote(operator, second, DATABASE, "local"), "55000");
            assertRejected(() -> promote(operator, first, DATABASE, "local"), "55000");
        }
        assertThat(version(first)).isOne();
        assertThat(evidenceCount(first)).isOne();
        assertUnpromoted(second);
    }

    @Test
    void promotion_shouldRejectDisabledRetiredMissingAndNullTargetsOrEnvironment() throws Exception {
        UUID disabled = registered();
        userService.disable(disabled);
        UUID retired = registered();
        userService.delete(retired);
        UUID active = registered();
        try (Connection operator = connection(OPERATOR)) {
            for (UUID id : new UUID[]{disabled, retired, UUID.randomUUID(), null}) {
                assertRejected(() -> promote(operator, id, DATABASE, "local"), "22023");
            }
            assertRejected(() -> promote(operator, active, "other_database", "local"), "42501");
            assertRejected(() -> promote(operator, active, null, "local"), "42501");
            assertRejected(() -> promote(operator, active, DATABASE, "prod"), "42501");
        }
        assertUnpromoted(active);
        assertThat(usableAdministrators()).isZero();
    }

    @Test
    void recovery_shouldRequireEligibleNewTargetAndNeverReenableInactiveAdministrators() throws Exception {
        UUID disabledAdmin = registered();
        userService.disable(disabledAdmin);
        addAdmin(disabledAdmin);
        UUID target = registered();
        assertThatThrownBy(() -> userService.enable(disabledAdmin)).isInstanceOf(UserAdministratorContinuityException.class);
        try (Connection operator = connection(OPERATOR)) { promote(operator, target, DATABASE, "local"); }
        assertThat(usableAdministrators()).isOne();
        assertThat(runtime.queryForObject("SELECT enabled FROM users WHERE id=?", Boolean.class, disabledAdmin)).isFalse();
    }

    @Test
    void rollbackAndOverflow_shouldLeaveNoPartialMembershipVersionOrEvidence() throws Exception {
        UUID id = registered();
        try (Connection operator = connection(OPERATOR)) {
            operator.setAutoCommit(false);
            promote(operator, id, DATABASE, "local");
            assertUnpromoted(id);
            operator.rollback();
        }
        assertUnpromoted(id);
        owner().update("UPDATE users SET credential_version=? WHERE id=?", Long.MAX_VALUE, id);
        try (Connection operator = connection(OPERATOR)) { assertRejected(() -> promote(operator, id, DATABASE, "local"), "22003"); }
        assertThat(roles(id)).containsExactly("ROLE_USER");
        assertThat(version(id)).isEqualTo(Long.MAX_VALUE);
        assertThat(evidenceCount(id)).isZero();
    }

    @Test
    void runtimeAndFlyway_shouldNotHaveOperatorAuthorityOrEvidenceMutation() throws Exception {
        UUID id = registered();
        try (Connection app = connection(RUNTIME); Connection migration = connection(MIGRATION); Connection operator = connection(OPERATOR)) {
            assertRejected(() -> promote(app, id, DATABASE, "local"), "42501");
            assertRejected(() -> promote(migration, id, DATABASE, "local"), "42501");
            assertRejected(() -> execute(app, "SET ROLE " + OPERATOR), "42501");
            assertRejected(() -> execute(operator, "SET ROLE " + MIGRATION), "42501");
            assertRejected(() -> execute(operator, "UPDATE public.users SET enabled=false"), "42501");
            assertRejected(() -> execute(operator, "INSERT INTO administrator_lifecycle.operator_identity(operator_login,database_name,environment_name) VALUES ('fake','fake','local')"), "42501");
            assertRejected(() -> execute(operator, "INSERT INTO administrator_lifecycle.promotion_evidence(target_user_id,operator_login,database_name,operation,credential_version_before,credential_version_after) VALUES ('" + id + "','forged','fake','ZERO_ADMIN_PROMOTION',0,1)"), "42501");
            UUID receipt = promote(operator, id, DATABASE, "local");
            assertThat(new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), OPERATOR, DB_PASSWORD))
                    .queryForObject("SELECT count(*) FROM administrator_lifecycle.promotion_evidence WHERE id=?", Long.class, receipt)).isOne();
            for (String identity : new String[]{RUNTIME, OPERATOR, MIGRATION}) {
                try (Connection actor = connection(identity)) {
                    for (String sql : new String[]{"UPDATE administrator_lifecycle.promotion_evidence SET operation='ZERO_ADMIN_PROMOTION'",
                            "DELETE FROM administrator_lifecycle.promotion_evidence", "TRUNCATE administrator_lifecycle.promotion_evidence"}) {
                        assertRejected(() -> execute(actor, sql), "42501");
                    }
                }
            }
        }
    }

    @Test
    void runtime_shouldRejectRoleDmlEvenAfterBlanketRegrantAndPreserveRegistration() throws Exception {
        UUID id = registered();
        try (Connection app = connection(RUNTIME); Connection admin = connection("postgres")) {
            for (int grantRound = 0; grantRound < 2; grantRound++) {
                for (String sql : new String[]{"INSERT INTO user_roles SELECT '" + id + "',id FROM roles WHERE name='ROLE_ADMIN'",
                        "UPDATE user_roles SET role_id=(SELECT id FROM roles WHERE name='ROLE_ADMIN') WHERE user_id='" + id + "'",
                        "DELETE FROM user_roles WHERE user_id='" + id + "'", "UPDATE roles SET name='ROLE_ATTACK' WHERE name='ROLE_USER'",
                        "DELETE FROM roles WHERE name='ROLE_ADMIN'", "INSERT INTO roles(name) VALUES ('ROLE_ATTACK')"}) {
                    assertRejected(() -> execute(app, sql), "42501");
                }
                assertRejected(() -> promote(app, id, DATABASE, "local"), "42501");
                execute(admin, "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + RUNTIME);
                execute(admin, "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RUNTIME);
            }
        }
        assertUnpromoted(id);
        assertThat(roles(registered())).containsExactly("ROLE_USER");
    }

    @Test
    void finalAdministrator_shouldRejectSelfAndOtherAdministratorHttpActionsAndRawSql() throws Exception {
        UUID id = registered();
        UUID inactive = registered();
        userService.disable(inactive);
        addAdmin(inactive);
        try (Connection operator = connection(OPERATOR)) { promote(operator, id, DATABASE, "local"); }
        String token = login(runtime.queryForObject("SELECT email FROM users WHERE id=?", String.class, id));
        mvc.perform(put("/api/v1/admin/users/{id}/disable", id).with(csrf()).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("USER_ADMINISTRATOR_CONTINUITY_REQUIRED"));
        mvc.perform(delete("/api/v1/admin/users/{id}", id).with(csrf()).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        try (Connection app = connection(RUNTIME)) {
            for (String sql : new String[]{"UPDATE users SET enabled=false,credential_version=credential_version+1 WHERE id='" + id + "'",
                    "UPDATE users SET deleted=true WHERE id='" + id + "'", "DELETE FROM users WHERE id='" + id + "'"}) {
                assertRejected(() -> execute(app, sql), "23514");
            }
        }
        assertThat(usableAdministrators()).isOne();
        UUID other = registered();
        addAdmin(other);
        String otherToken = login(runtime.queryForObject("SELECT email FROM users WHERE id=?", String.class, other));
        mvc.perform(put("/api/v1/admin/users/{id}/disable", other).with(csrf()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/users/{id}", id).with(csrf()).header("Authorization", "Bearer " + otherToken)).andExpect(status().isForbidden());
        assertThatThrownBy(() -> userService.delete(id)).isInstanceOf(UserAdministratorContinuityException.class);
    }

    @Test
    void simultaneousPromotions_shouldBlockInSharedDomainAndCommitExactlyOne() throws Exception {
        UUID first = registered();
        UUID second = registered();
        AtomicInteger waitingPid = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection winner = connection(OPERATOR)) {
            winner.setAutoCommit(false);
            promote(winner, first, DATABASE, "local");
            var waiter = executor.submit(() -> {
                try (Connection losing = connection(OPERATOR)) {
                    waitingPid.set(pid(losing));
                    try { promote(losing, second, DATABASE, "local"); return "unexpected-success"; }
                    catch (SQLException error) { return error.getSQLState(); }
                }
            });
            awaitGlobalWait(waitingPid);
            assertThat(owner().queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='advisory' AND objsubid=1", Long.class, waitingPid.get())).isZero();
            winner.commit();
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo("55000");
        } finally { executor.shutdownNow(); }
        assertThat(usableAdministrators()).isOne();
        assertThat(evidenceCount(first)).isOne();
        assertUnpromoted(second);
    }

    @Test
    void concurrentDisablements_shouldBlockAndRetainOneAdministrator() throws Exception { concurrentRemoval(false); }

    @Test
    void concurrentRetirementAndDisablement_shouldBlockAndRetainOneAdministrator() throws Exception { concurrentRemoval(true); }

    private void concurrentRemoval(boolean retireFirst) throws Exception {
        UUID first = registered();
        UUID second = registered();
        addAdmin(first);
        addAdmin(second);
        AtomicInteger waitingPid = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection winner = connection(RUNTIME)) {
            winner.setAutoCommit(false);
            execute(winner, "UPDATE users SET " + (retireFirst ? "deleted=true" : "enabled=false,credential_version=credential_version+1") + " WHERE id='" + first + "'");
            var waiter = executor.submit(() -> {
                try (Connection losing = connection(RUNTIME)) {
                    waitingPid.set(pid(losing));
                    try { execute(losing, "UPDATE users SET enabled=false,credential_version=credential_version+1 WHERE id='" + second + "'"); return "unexpected-success"; }
                    catch (SQLException error) { return error.getSQLState(); }
                }
            });
            awaitGlobalWait(waitingPid);
            winner.commit();
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo("23514");
        } finally { executor.shutdownNow(); }
        assertThat(usableAdministrators()).isOne();
        assertThat(runtime.queryForObject("SELECT enabled AND NOT deleted FROM users WHERE id=?", Boolean.class, second)).isTrue();
    }

    @Test
    void lockTimeoutAndRollback_shouldReleaseDomainWithoutPartialPromotion() throws Exception {
        UUID id = registered();
        try (Connection blocker = connection(MIGRATION); Connection operator = connection(OPERATOR)) {
            blocker.setAutoCommit(false);
            execute(blocker, "SELECT pg_advisory_xact_lock(1094995278,1)");
            operator.setAutoCommit(false);
            execute(operator, "SET LOCAL lock_timeout='200ms'");
            assertRejected(() -> promote(operator, id, DATABASE, "local"), "55P03");
            operator.rollback();
            assertUnpromoted(id);
            blocker.rollback();
            promote(operator, id, DATABASE, "local");
            operator.commit();
        }
        assertThat(evidenceCount(id)).isOne();
    }

    @Test
    void promotion_shouldAcquireGlobalDomainBeforeWaitingForExistingUserLock() throws Exception {
        UUID id = registered();
        AtomicInteger waitingPid = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection commerce = connection(RUNTIME)) {
            commerce.setAutoCommit(false);
            execute(commerce, "SELECT pg_advisory_xact_lock(hashtextextended('" + id + "',0))");
            var promotion = executor.submit(() -> {
                try (Connection operator = connection(OPERATOR)) {
                    waitingPid.set(pid(operator));
                    return promote(operator, id, DATABASE, "local");
                }
            });
            awaitAdvisoryWait(waitingPid, 1);
            assertThat(owner().queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='advisory' AND classid=1094995278 AND objid=1 AND objsubid=2 AND granted", Long.class, waitingPid.get())).isOne();
            assertUnpromoted(id);
            commerce.rollback();
            assertThat(promotion.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally { executor.shutdownNow(); }
        assertThat(evidenceCount(id)).isOne();
    }

    @Test
    void bulkRemovalAndHigherIsolation_shouldFailWithoutBreakingContinuity() throws Exception {
        UUID first = registered();
        UUID second = registered();
        addAdmin(first);
        addAdmin(second);
        try (Connection app = connection(RUNTIME)) {
            assertRejected(() -> execute(app, "UPDATE users SET enabled=false,credential_version=credential_version+1"), "23514");
            app.setAutoCommit(false);
            execute(app, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            assertRejected(() -> execute(app, "UPDATE users SET deleted=true WHERE id='" + first + "'"), "25001");
            app.rollback();
        }
        assertThat(usableAdministrators()).isEqualTo(2);
    }

    @Test
    void restoredDatabase_shouldPreserveEvidenceAndNeverAutomaticallyElevateOrReplay() throws Exception {
        UUID id = registered();
        UUID former = registered();
        UUID previousReceipt;
        try (Connection operator = connection(OPERATOR)) { previousReceipt = promote(operator, former, DATABASE, "local"); }
        // Trusted fixture maintenance represents a legacy zero-admin recovery point with retained success evidence.
        owner().update("DELETE FROM user_roles WHERE user_id=? AND role_id=(SELECT id FROM roles WHERE name='ROLE_ADMIN')", former);
        userService.delete(former);
        long evidenceBefore = owner().queryForObject("SELECT count(*) FROM administrator_lifecycle.promotion_evidence", Long.class);
        String restored = "restored_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connection("postgres")) { execute(admin, "CREATE DATABASE " + restored + " OWNER " + MIGRATION); }
        var dump = POSTGRES.execInContainer("pg_dump", "-U", "postgres", "-d", DATABASE, "-Fc", "-f", "/tmp/lifecycle.dump");
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
        var restore = POSTGRES.execInContainer("pg_restore", "-U", "postgres", "-d", restored, "--exit-on-error", "/tmp/lifecycle.dump");
        assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
        String restoredUrl = POSTGRES.getJdbcUrl().replace("/" + DATABASE, "/" + restored);
        Flyway.configure().dataSource(restoredUrl, MIGRATION, DB_PASSWORD).load().validate();
        JdbcTemplate restoredOwner = new JdbcTemplate(new DriverManagerDataSource(restoredUrl, MIGRATION, DB_PASSWORD));
        assertThat(restoredOwner.queryForObject("SELECT count(*) FROM administrator_lifecycle.promotion_evidence", Long.class)).isEqualTo(evidenceBefore);
        assertThat(restoredOwner.queryForObject("SELECT target_user_id FROM administrator_lifecycle.promotion_evidence WHERE id=?", UUID.class, previousReceipt)).isEqualTo(former);
        assertThat(restoredOwner.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, id)).isZero();
        try (Connection restoredOperator = DriverManager.getConnection(restoredUrl, OPERATOR, DB_PASSWORD)) {
            assertRejected(() -> promote(restoredOperator, id, restored, "local"), "42501");
        }
        assertThat(restoredOwner.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE r.name='ROLE_ADMIN'", Long.class)).isZero();
        try (Connection app = DriverManager.getConnection(restoredUrl, RUNTIME, DB_PASSWORD)) {
            assertRejected(() -> execute(app, "INSERT INTO user_roles SELECT '" + id + "',id FROM roles WHERE name='ROLE_ADMIN'"), "42501");
        }
        var configuration = POSTGRES.execInContainer("psql", "-X", "-U", "postgres", "-d", restored,
                "--set=operator_login=" + OPERATOR, "--set=runtime_login=" + RUNTIME,
                "--set=migration_login=" + MIGRATION, "--set=expected_database=" + restored,
                "--file=/tmp/configure-operator.sql");
        assertThat(configuration.getExitCode()).as(configuration.getStderr()).isZero();
        try (Connection restoredOperator = DriverManager.getConnection(restoredUrl, OPERATOR, DB_PASSWORD)) {
            promote(restoredOperator, id, restored, "local");
            assertRejected(() -> promote(restoredOperator, id, restored, "local"), "55000");
        }
        assertThat(restoredOwner.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, id)).isOne();
        assertThat(restoredOwner.queryForObject("SELECT count(*) FROM administrator_lifecycle.promotion_evidence", Long.class)).isEqualTo(evidenceBefore + 1);
    }

    private UUID registered() {
        String email = "local-" + UUID.randomUUID() + "@example.invalid";
        authService.register(new RegisterRequestDTO(email, USER_PASSWORD, USER_PASSWORD, "Local", "Owner"));
        return runtime.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, email);
    }

    private void addAdmin(UUID id) { owner().update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", id); }
    private java.util.List<String> roles(UUID id) { return runtime.queryForList("SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?", String.class, id); }
    private long version(UUID id) { return runtime.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, id); }
    private long usableAdministrators() { return runtime.queryForObject("SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.name='ROLE_ADMIN' AND u.enabled AND NOT u.deleted", Long.class); }
    private long evidenceCount(UUID id) { return owner().queryForObject("SELECT count(*) FROM administrator_lifecycle.promotion_evidence WHERE target_user_id=?", Long.class, id); }
    private void assertUnpromoted(UUID id) { assertThat(roles(id)).containsExactly("ROLE_USER"); assertThat(version(id)).isZero(); assertThat(evidenceCount(id)).isZero(); }
    private static JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), MIGRATION, DB_PASSWORD)); }
    private static Connection connection(String identity) throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), identity, DB_PASSWORD); }
    private static void execute(Connection connection, String sql) throws SQLException { try (Statement statement = connection.createStatement()) { statement.execute(sql); } }
    private static int pid(Connection connection) throws SQLException { try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) { result.next(); return result.getInt(1); } }
    private static UUID promote(Connection connection, UUID id, String database, String environment) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("SELECT administrator_lifecycle.promote_zero_administrator(?,?,?)")) {
            sql.setObject(1, id); sql.setString(2, database); sql.setString(3, environment);
            try (ResultSet result = sql.executeQuery()) { result.next(); return result.getObject(1, UUID.class); }
        }
    }
    private static void assertRejected(SqlOperation operation, String state) { assertThatThrownBy(operation::run).isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(state)); }
    @FunctionalInterface private interface SqlOperation { void run() throws SQLException; }
    private static void awaitGlobalWait(AtomicInteger pid) {
        awaitAdvisoryWait(pid, 2);
    }
    private static void awaitAdvisoryWait(AtomicInteger pid, int keySpace) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted AND objsubid=? AND (?=1 OR (classid=1094995278 AND objid=1)))", Boolean.class, pid.get(), keySpace, keySpace))) { return; }
            Thread.onSpinWait();
        }
        throw new AssertionError("Independent transaction did not block on expected advisory key space " + keySpace);
    }
    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Login(email, USER_PASSWORD)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }
    private record Login(String email, String password) { }
}
