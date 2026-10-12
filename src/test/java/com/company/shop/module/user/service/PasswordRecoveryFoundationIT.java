package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.company.shop.module.user.dto.LoginRequestDTO;
import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.dto.PasswordRecoveryReplacement;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.module.user.exception.PasswordRecoveryRejectedException;
import com.company.shop.module.user.repository.PasswordRecoveryChallengeRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.security.AuthService;

import jakarta.validation.Validator;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PasswordRecoveryFoundationIT {
    private static final String DATABASE = "password_recovery_test";
    private static final String MIGRATION = "recovery_migration";
    private static final String RUNTIME = "recovery_runtime";
    private static final String OPERATOR = "recovery_operator";
    private static final String DB_PASSWORD = "synthetic_database_password";
    private static final String OLD_PASSWORD = "StrongPassword123!";
    private static final String NEW_PASSWORD = "ChangedPassword456!";
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
        } catch (SQLException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @Autowired private AuthService auth;
    @Autowired private UserService userService;
    @Autowired private UserRepository users;
    @Autowired private PasswordRecoveryChallengeRepository challenges;
    @Autowired private PasswordEncoder encoder;
    @Autowired private Validator validator;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mvc;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;
    private PasswordRecoveryService service;

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
        owner().execute("TRUNCATE users CASCADE");
        service = service(encoder, transactions);
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/configure-local-admin-operator.sql"), "/tmp/configure-recovery-operator.sql");
        var result = POSTGRES.execInContainer("psql", "-X", "-U", "postgres", "-d", DATABASE,
                "--set=operator_login=" + OPERATOR, "--set=runtime_login=" + RUNTIME,
                "--set=migration_login=" + MIGRATION, "--set=expected_database=" + DATABASE,
                "--file=/tmp/configure-recovery-operator.sql");
        assertThat(result.getExitCode()).isZero();
    }

    @Test
    void consume_shouldChangePasswordVersionAndJwtAuthorityWithoutPersistingOrLoggingSecret(CapturedOutput output) throws Exception {
        UUID user = registered();
        String email = email(user);
        String oldJwt = auth.login(new LoginRequestDTO(email, OLD_PASSWORD)).getToken();
        var capture = issued(user);
        UUID challenge = selector(capture);
        assertThat(jdbc.queryForObject("SELECT expires_at-created_at=INTERVAL '15 minutes' FROM password_recovery_challenges WHERE id=?", Boolean.class, challenge)).isTrue();
        service.consume(capture.token(), replacement(NEW_PASSWORD));
        assertThat(version(user)).isOne();
        assertThat(encoder.matches(NEW_PASSWORD, password(user))).isTrue();
        assertThat(encoder.matches(OLD_PASSWORD, password(user))).isFalse();
        assertThat(row(challenge).get("consumed_at")).isNotNull();
        assertThat(row(challenge).get("revoked_at")).isNull();
        assertThat(jdbc.queryForMap("SELECT email,enabled,deleted FROM users WHERE id=?", user))
                .containsEntry("email", email).containsEntry("enabled", true).containsEntry("deleted", false);
        assertThat(jdbc.queryForList("SELECT r.name FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?", String.class, user)).containsExactly("ROLE_USER");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldJwt)).andExpect(status().isForbidden());
        String newJwt = auth.login(new LoginRequestDTO(email, NEW_PASSWORD)).getToken();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + newJwt)).andExpect(status().isOk());
        assertThatThrownBy(() -> auth.login(new LoginRequestDTO(email, OLD_PASSWORD))).isInstanceOf(com.company.shop.module.user.exception.InvalidCredentialsException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isZero();
        String persisted = jdbc.queryForObject("SELECT row_to_json(c)::text FROM password_recovery_challenges c WHERE id=?", String.class, challenge);
        assertThat(persisted != null && !persisted.contains(capture.token())).isTrue();
        assertThat(!output.getAll().contains(capture.token())).isTrue();
        assertRejected(capture);
        assertThat(version(user)).isOne();
    }

    @Test
    void issueAndConsume_shouldExcludeUnknownDisabledRetiredAndAdministratorUsers() {
        var capture = new MemoryRecoveryCapture();
        assertThatThrownBy(() -> capture.issue(service, UUID.randomUUID())).isInstanceOf(PasswordRecoveryRejectedException.class);
        UUID disabled = registered();
        var disabledToken = issued(disabled);
        userService.disable(disabled);
        assertRejected(disabledToken);
        assertThatThrownBy(() -> capture.issue(service, disabled)).isInstanceOf(PasswordRecoveryRejectedException.class);
        userService.enable(disabled);
        assertRejected(disabledToken);
        UUID retired = registered();
        var retiredToken = issued(retired);
        userService.delete(retired);
        assertRejected(retiredToken);
        assertThatThrownBy(() -> capture.issue(service, retired)).isInstanceOf(PasswordRecoveryRejectedException.class);
        UUID admin = registered();
        var beforePromotion = issued(admin);
        owner().update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", admin);
        assertRejected(beforePromotion);
        assertThatThrownBy(() -> capture.issue(service, admin)).isInstanceOf(PasswordRecoveryRejectedException.class);
        owner().update("UPDATE users SET enabled=false,credential_version=credential_version+1 WHERE id=?", adminWithPeer(admin));
        assertThatThrownBy(() -> capture.issue(service, admin)).isInstanceOf(PasswordRecoveryRejectedException.class);
        assertThat(capture.deliveries()).isZero();
        assertThat(capture.token() == null).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted FROM users WHERE id=?", Boolean.class, retired)).isTrue();
        assertThatThrownBy(() -> auth.register(new RegisterRequestDTO(email(retired), OLD_PASSWORD, OLD_PASSWORD, "T", "U")))
                .isInstanceOf(com.company.shop.module.user.exception.UserAlreadyExistsException.class);
    }

    @Test
    void consume_shouldEnforceUtf8ConfirmationAndFinalExpiryGuardWithoutMutation() {
        UUID user = registered();
        var capture = issued(user);
        for (var replacement : new PasswordRecoveryReplacement[]{replacement("short"), replacement("🔐".repeat(19)),
                new PasswordRecoveryReplacement(NEW_PASSWORD, "MismatchPassword789!")}) {
            assertThatThrownBy(() -> service.consume(capture.token(), replacement)).isInstanceOf(PasswordRecoveryRejectedException.class);
        }
        assertThat(version(user)).isZero();
        assertThat(row(selector(capture)).get("consumed_at")).isNull();
        String boundary = "🔐".repeat(18);
        service.consume(capture.token(), replacement(boundary));
        auth.login(new LoginRequestDTO(email(user), boundary));
        var expired = historical(user, Instant.now().minusSeconds(1));
        assertRejected(expired);
        var expiring = historicalAfterRevocation(user, Instant.now().plusSeconds(1));
        PasswordEncoder crossesDeadline = new PasswordEncoder() {
            @Override public String encode(CharSequence raw) {
                String encoded = encoder.encode(raw);
                while (challenges.currentDatabaseTime().isBefore(deadline(selector(expiring)))) Thread.onSpinWait();
                return encoded;
            }
            @Override public boolean matches(CharSequence raw, String encoded) { return encoder.matches(raw, encoded); }
        };
        assertThatThrownBy(() -> service(crossesDeadline, transactions).consume(expiring.token(), replacement(NEW_PASSWORD)))
                .isInstanceOf(PasswordRecoveryRejectedException.class);
        assertThat(version(user)).isOne();
        assertThat(row(selector(expiring)).get("consumed_at")).isNull();
    }

    @Test
    void supersessionAndCredentialChanges_shouldPermanentlyRevokeOlderAuthority() {
        UUID user = registered();
        var first = issued(user);
        var second = issued(user);
        assertThat(row(selector(first)).get("revoked_at")).isNotNull();
        assertRejected(first);
        asUser(user, () -> userService.changeCurrentUserPassword(new PasswordChangeRequestDTO(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)));
        assertRejected(second);
        assertThat(version(user)).isOne();
        var wrong = PasswordRecoveryToken.generate();
        var valid = issued(user);
        assertThatThrownBy(() -> service.consume("v1." + selector(valid) + "." + wrong.encoded().split("\\.")[2], replacement(OLD_PASSWORD)))
                .isInstanceOf(PasswordRecoveryRejectedException.class);
        assertThat(version(user)).isOne();
        assertThat(row(selector(valid)).get("consumed_at")).isNull();
    }

    @Test
    void concurrentIssuance_shouldWaitInExistingDomainAndCommitOnlyOneOpenChallenge() throws Exception {
        UUID user = registered();
        var first = new MemoryRecoveryCapture();
        var second = new MemoryRecoveryCapture();
        var winner = new ObservedTransactions();
        winner.pauseCommit = true;
        var waiter = new ObservedTransactions();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> first.issue(service(encoder, winner), user));
            await(winner.commitReady);
            assertThat(first.token() == null).isTrue();
            var b = executor.submit(() -> second.issue(service(encoder, waiter), user));
            try { awaitAdvisoryWait(waiter.pid, 2); }
            finally { winner.commitRelease.countDown(); }
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_recovery_challenges WHERE consumed_at IS NULL AND revoked_at IS NULL", Long.class)).isOne();
        assertRejected(first);
        service.consume(second.token(), replacement(NEW_PASSWORD));
        assertThat(version(user)).isOne();
    }

    @Test
    void simultaneousConsumers_shouldCommitAtMostOnePasswordChange() throws Exception {
        UUID user = registered();
        var capture = issued(user);
        var winner = new ObservedTransactions();
        winner.pauseCommit = true;
        var waiter = new ObservedTransactions();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> service(encoder, winner).consume(capture.token(), replacement(NEW_PASSWORD)));
            await(winner.commitReady);
            var b = executor.submit(() -> attemptConsume(service(encoder, waiter), capture));
            try { awaitAdvisoryWait(waiter.pid, 2); }
            finally { winner.commitRelease.countDown(); }
            a.get(10, TimeUnit.SECONDS);
            assertThat(b.get(10, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(version(user)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_recovery_challenges WHERE consumed_at IS NOT NULL", Long.class)).isOne();
    }

    @Test
    void issuanceAndConsumption_shouldSerializeBothWinningOrders() throws Exception {
        for (boolean issueFirst : new boolean[]{true, false}) {
            UUID user = registered();
            var old = issued(user);
            var next = new MemoryRecoveryCapture();
            var winner = new ObservedTransactions();
            winner.pauseCommit = true;
            var waiter = new ObservedTransactions();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> {
                    if (issueFirst) next.issue(service(encoder, winner), user);
                    else service(encoder, winner).consume(old.token(), replacement(NEW_PASSWORD));
                });
                await(winner.commitReady);
                var b = executor.submit(() -> {
                    if (issueFirst) return attemptConsume(service(encoder, waiter), old);
                    next.issue(service(encoder, waiter), user);
                    return true;
                });
                try { awaitAdvisoryWait(waiter.pid, 2); }
                finally { winner.commitRelease.countDown(); }
                a.get(10, TimeUnit.SECONDS);
                assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(!issueFirst);
            }
            assertThat(version(user)).isEqualTo(issueFirst ? 0L : 1L);
            service.consume(next.token(), replacement(NEW_PASSWORD));
            assertThat(version(user)).isEqualTo(issueFirst ? 1L : 2L);
        }
    }

    @Test
    void userLifecycleWinning_shouldMakeWaitingResetFailClosed() throws Exception {
        for (String transition : new String[]{"password", "retire", "disable", "promote"}) {
            UUID user = registered();
            var capture = issued(user);
            var waiter = new ObservedTransactions();
            try (Connection winner = connection(transition.equals("promote") ? OPERATOR : RUNTIME);
                    var executor = Executors.newSingleThreadExecutor()) {
                winner.setAutoCommit(false);
                execute(winner, "SELECT pg_advisory_xact_lock(1094995278,1)");
                execute(winner, "SELECT pg_advisory_xact_lock(hashtextextended('" + user + "',0))");
                if (transition.equals("promote")) {
                    execute(winner, "SELECT administrator_lifecycle.promote_zero_administrator('" + user + "','" + DATABASE + "','local')");
                } else if (transition.equals("retire")) {
                    execute(winner, "UPDATE users SET deleted=true WHERE id='" + user + "'");
                } else {
                    execute(winner, "UPDATE users SET credential_version=credential_version+1," +
                            (transition.equals("disable") ? "enabled=false" : "password='" + encoder.encode(NEW_PASSWORD) + "'") + " WHERE id='" + user + "'");
                }
                var reset = executor.submit(() -> attemptConsume(service(encoder, waiter), capture));
                awaitAdvisoryWait(waiter.pid, 2);
                winner.commit();
                assertThat(reset.get(10, TimeUnit.SECONDS)).isFalse();
            }
            assertThat(row(selector(capture)).get("consumed_at")).isNull();
            if (transition.equals("disable")) { userService.enable(user); assertRejected(capture); }
        }
    }

    @Test
    void resetWinning_shouldCommitBeforePasswordRetirementDisableAndPromotion() throws Exception {
        for (String transition : new String[]{"password", "retire", "disable", "promote"}) {
            UUID user = registered();
            var capture = issued(user);
            var winner = new ObservedTransactions();
            winner.pauseCommit = true;
            AtomicInteger waiterPid = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var reset = executor.submit(() -> service(encoder, winner).consume(capture.token(), replacement(NEW_PASSWORD)));
                await(winner.commitReady);
                var next = executor.submit(() -> {
                    try (Connection connection = connection(transition.equals("promote") ? OPERATOR : RUNTIME)) {
                        connection.setAutoCommit(false);
                        waiterPid.set(pid(connection));
                        execute(connection, "SELECT pg_advisory_xact_lock(1094995278,1)");
                        execute(connection, "SELECT pg_advisory_xact_lock(hashtextextended('" + user + "',0))");
                        if (transition.equals("promote")) execute(connection, "SELECT administrator_lifecycle.promote_zero_administrator('" + user + "','" + DATABASE + "','local')");
                        else if (transition.equals("retire")) execute(connection, "UPDATE users SET deleted=true WHERE id='" + user + "'");
                        else if (transition.equals("disable")) execute(connection, "UPDATE users SET enabled=false,credential_version=credential_version+1 WHERE id='" + user + "'");
                        connection.commit();
                        if (transition.equals("password")) {
                            assertThatThrownBy(() -> asUser(user, () -> userService.changeCurrentUserPassword(new PasswordChangeRequestDTO(OLD_PASSWORD, "AnotherPassword789!", "AnotherPassword789!"))))
                                    .isInstanceOf(com.company.shop.module.user.exception.CurrentPasswordIncorrectException.class);
                        }
                    }
                    return true;
                });
                try { awaitAdvisoryWait(waiterPid, 2); }
                finally { winner.commitRelease.countDown(); }
                reset.get(10, TimeUnit.SECONDS);
                assertThat(next.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(row(selector(capture)).get("consumed_at")).isNotNull();
            assertThat(encoder.matches(NEW_PASSWORD, password(user))).isTrue();
        }
    }

    @Test
    void lockOrderingTimeoutAndRollback_shouldPreserveChallengeAndReleaseDomains() throws Exception {
        UUID user = registered();
        var capture = issued(user);
        var waiting = new ObservedTransactions();
        try (Connection commerce = connection(RUNTIME); var executor = Executors.newSingleThreadExecutor()) {
            commerce.setAutoCommit(false);
            execute(commerce, "SELECT pg_advisory_xact_lock(hashtextextended('" + user + "',0))");
            var reset = executor.submit(() -> service(encoder, waiting).consume(capture.token(), replacement(NEW_PASSWORD)));
            awaitAdvisoryWait(waiting.pid, 1);
            assertThat(owner().queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='advisory' AND classid=1094995278 AND objid=1 AND objsubid=2 AND granted", Long.class, waiting.pid.get())).isOne();
            assertThat(owner().queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='tuple'", Long.class, waiting.pid.get())).isZero();
            commerce.rollback();
            reset.get(10, TimeUnit.SECONDS);
        }
        var fresh = issued(user);
        var timeout = new ObservedTransactions();
        timeout.lockTimeout = true;
        try (Connection blocker = connection(RUNTIME)) {
            blocker.setAutoCommit(false);
            execute(blocker, "SELECT pg_advisory_xact_lock(1094995278,1)");
            assertSqlState(() -> service(encoder, timeout).consume(fresh.token(), replacement(OLD_PASSWORD)), "55P03");
            blocker.rollback();
        }
        var rollback = new ObservedTransactions();
        rollback.rejectCommit = true;
        var failedCapture = new MemoryRecoveryCapture();
        assertThatThrownBy(() -> failedCapture.issue(service(encoder, rollback), user)).isInstanceOf(IllegalStateException.class);
        assertThat(failedCapture.token() == null).isTrue();
        assertThat(failedCapture.deliveries()).isZero();
        assertThat(row(selector(fresh)).get("revoked_at")).isNull();
        assertThatThrownBy(() -> service(encoder, rollback).consume(fresh.token(), replacement(OLD_PASSWORD))).isInstanceOf(IllegalStateException.class);
        assertThat(row(selector(fresh)).get("consumed_at")).isNull();
        assertThat(version(user)).isOne();
        service.consume(fresh.token(), replacement(OLD_PASSWORD));
        assertThat(version(user)).isEqualTo(2);
    }

    @Test
    void deadlockVictim_shouldRollbackWithoutPartialCredentialOrConsumption() throws Exception {
        UUID user = registered();
        var capture = issued(user);
        var waiting = new ObservedTransactions();
        try (Connection inverse = connection(RUNTIME); var executor = Executors.newSingleThreadExecutor()) {
            inverse.setAutoCommit(false);
            execute(inverse, "SELECT pg_advisory_xact_lock(hashtextextended('" + user + "',0))");
            var reset = executor.submit(() -> {
                try { service(encoder, waiting).consume(capture.token(), replacement(NEW_PASSWORD)); return "committed"; }
                catch (RuntimeException exception) { return sqlState(exception); }
            });
            awaitAdvisoryWait(waiting.pid, 1);
            String inverseResult;
            try { execute(inverse, "SELECT pg_advisory_xact_lock(1094995278,1)"); inverse.commit(); inverseResult = "committed"; }
            catch (SQLException exception) { inverseResult = exception.getSQLState(); inverse.rollback(); }
            String resetResult = reset.get(10, TimeUnit.SECONDS);
            assertThat(inverseResult.equals("40P01") || resetResult.equals("40P01")).isTrue();
            boolean consumed = row(selector(capture)).get("consumed_at") != null;
            assertThat(version(user)).isEqualTo(consumed ? 1L : 0L);
            assertThat(encoder.matches(NEW_PASSWORD, password(user))).isEqualTo(consumed);
            if (!consumed) service.consume(capture.token(), replacement(NEW_PASSWORD));
        }
    }

    @Test
    void schema_shouldEnforceImmutableFiniteOneTimeStateAndRuntimePrivileges() throws Exception {
        UUID user = registered();
        var capture = issued(user);
        UUID id = selector(capture);
        for (String change : new String[]{"id=public.uuid_generate_v4()", "user_id=public.uuid_generate_v4()", "format_version=2",
                "verifier=set_byte(verifier,0,get_byte(verifier,0)#1)", "issued_credential_version=1", "created_at=created_at-INTERVAL '1 second'", "expires_at=expires_at+INTERVAL '1 second'"}) {
            assertSqlState(() -> jdbc.update("UPDATE password_recovery_challenges SET " + change + " WHERE id=?", id), "23514");
        }
        var token = PasswordRecoveryToken.generate();
        assertSqlState(() -> insertHistorical(token, user, version(user), Instant.now().plusSeconds(600)), "23505");
        assertSqlState(() -> jdbc.update("DELETE FROM password_recovery_challenges WHERE id=?", id), "42501");
        owner().execute("GRANT TRUNCATE ON password_recovery_challenges TO " + RUNTIME);
        assertSqlState(() -> jdbc.execute("TRUNCATE password_recovery_challenges"), "42501");
        assertSqlState(() -> jdbc.update("DELETE FROM users WHERE id=?", user), "23503");
        assertSqlState(() -> jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", user), "42501");
        service.consume(capture.token(), replacement(NEW_PASSWORD));
        assertSqlState(() -> jdbc.update("UPDATE password_recovery_challenges SET consumed_at=NULL WHERE id=?", id), "23514");
        assertSqlState(() -> jdbc.update("UPDATE password_recovery_challenges SET revoked_at=clock_timestamp() WHERE id=?", id), "23514");
        var next = issued(user);
        jdbc.update("UPDATE password_recovery_challenges SET revoked_at=clock_timestamp() WHERE id=?", selector(next));
        assertSqlState(() -> jdbc.update("UPDATE password_recovery_challenges SET revoked_at=NULL WHERE id=?", selector(next)), "23514");
        try (Connection unsupported = connection(RUNTIME)) {
            unsupported.setAutoCommit(false);
            execute(unsupported, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            assertSqlState(() -> { try { execute(unsupported, "UPDATE password_recovery_challenges SET revoked_at=revoked_at"); } catch (SQLException exception) { throw new IllegalStateException(exception); } }, "25001");
            unsupported.rollback();
        }
    }

    @Test
    void schemaChecks_shouldRejectMalformedVerifiersFormatsAndInfiniteOrIncorrectLifetimes() {
        UUID user = registered();
        for (String values : new String[]{"1,decode(repeat('00',31),'hex'),0,'2026-01-01 00:00:00+00','2026-01-01 00:15:00+00'",
                "2,decode(repeat('00',32),'hex'),0,'2026-01-01 00:00:00+00','2026-01-01 00:15:00+00'",
                "1,decode(repeat('00',32),'hex'),-1,'2026-01-01 00:00:00+00','2026-01-01 00:15:00+00'",
                "1,decode(repeat('00',32),'hex'),0,'infinity','infinity'",
                "1,decode(repeat('00',32),'hex'),0,'2026-01-01 00:00:00+00','2026-01-01 00:00:00+00'"}) {
            assertSqlState(() -> owner().update("INSERT INTO password_recovery_challenges(id,user_id,format_version,verifier,issued_credential_version,created_at,expires_at) VALUES (?, ?," + values + ")", UUID.randomUUID(), user), "23514");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_recovery_challenges", Long.class)).isZero();
    }

    @Test
    void overflowAndDatabaseEligibility_shouldFailWithoutPartialState() {
        UUID user = registered();
        owner().update("UPDATE users SET credential_version=? WHERE id=?", Long.MAX_VALUE, user);
        var capture = issued(user);
        assertThatThrownBy(() -> service.consume(capture.token(), replacement(NEW_PASSWORD))).isInstanceOf(ArithmeticException.class);
        assertThat(version(user)).isEqualTo(Long.MAX_VALUE);
        assertThat(row(selector(capture)).get("consumed_at")).isNull();
        assertThat(encoder.matches(OLD_PASSWORD, password(user))).isTrue();
        UUID disabled = registered();
        userService.disable(disabled);
        assertSqlState(() -> insertHistorical(PasswordRecoveryToken.generate(), disabled, version(disabled), Instant.now().plusSeconds(10)), "23514");
        UUID admin = registered();
        owner().update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", admin);
        assertSqlState(() -> insertHistorical(PasswordRecoveryToken.generate(), admin, version(admin), Instant.now().plusSeconds(10)), "23514");
        assertSqlState(() -> insertHistorical(PasswordRecoveryToken.generate(), UUID.randomUUID(), 0, Instant.now().plusSeconds(10)), "23514");
    }

    @Test
    void restoredAuthority_shouldRemainUnreachableAndBeExplicitlyInvalidatedBeforeFutureActivation() throws Exception {
        UUID user = registered();
        var capture = issued(user);
        String restored = "recovery_restore_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connection("postgres")) { execute(admin, "CREATE DATABASE " + restored + " OWNER " + MIGRATION); }
        try {
            var dump = POSTGRES.execInContainer("pg_dump", "-U", "postgres", "-d", DATABASE, "-Fc", "-f", "/tmp/recovery-foundation.dump");
            assertThat(dump.getExitCode()).isZero();
            service.consume(capture.token(), replacement(NEW_PASSWORD));
            var restore = POSTGRES.execInContainer("pg_restore", "-U", "postgres", "-d", restored, "--exit-on-error", "/tmp/recovery-foundation.dump");
            assertThat(restore.getExitCode()).isZero();
            String url = POSTGRES.getJdbcUrl().replace("/" + DATABASE, "/" + restored);
            Flyway.configure().dataSource(url, MIGRATION, DB_PASSWORD).load().validate();
            JdbcTemplate recovered = new JdbcTemplate(new DriverManagerDataSource(url, MIGRATION, DB_PASSWORD));
            assertThat(recovered.queryForObject("SELECT count(*) FROM password_recovery_challenges WHERE consumed_at IS NULL AND revoked_at IS NULL", Long.class)).isOne();
            assertThat(recovered.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, user)).isZero();
            recovered.update("UPDATE password_recovery_challenges SET revoked_at=clock_timestamp() WHERE consumed_at IS NULL AND revoked_at IS NULL");
            assertThat(recovered.queryForObject("SELECT count(*) FROM password_recovery_challenges WHERE consumed_at IS NULL AND revoked_at IS NULL", Long.class)).isZero();
            assertThat(recovered.update("UPDATE password_recovery_challenges SET consumed_at=clock_timestamp() WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at>clock_timestamp()", selector(capture))).isZero();
            assertThat(context.getBeansOfType(PasswordRecoveryService.class)).isEmpty();
        } finally {
            POSTGRES.execInContainer("rm", "-f", "/tmp/recovery-foundation.dump");
            try (Connection admin = connection("postgres")) { execute(admin, "DROP DATABASE " + restored); }
        }
    }

    @Test
    void application_shouldHaveNoRecoveryBeanEndpointWorkerOrPackagedCapture() throws Exception {
        assertThat(context.getBeansOfType(PasswordRecoveryService.class)).isEmpty();
        assertThat(context.getBeansOfType(MemoryRecoveryCapture.class)).isEmpty();
        assertThat(mappings.getHandlerMethods().values().stream().noneMatch(method -> method.getBeanType().getName().contains("PasswordRecovery"))).isTrue();
        try (var jar = new java.util.jar.JarFile("target/enterprise-shop-0.0.1-SNAPSHOT.jar")) {
            assertThat(jar.stream().noneMatch(entry -> entry.getName().endsWith("/MemoryRecoveryCapture.class"))).isTrue();
        }
        UUID user = registered();
        var capture = issued(user);
        jdbc.update("UPDATE password_recovery_challenges SET revoked_at=clock_timestamp() WHERE id=?", selector(capture));
        assertRejected(capture);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_recovery_challenges", Long.class)).isOne();
    }

    private PasswordRecoveryService service(PasswordEncoder passwordEncoder, PlatformTransactionManager manager) {
        return new PasswordRecoveryService(users, challenges, passwordEncoder, validator, manager);
    }
    private UUID registered() {
        String address = "recovery-" + UUID.randomUUID() + "@example.invalid";
        auth.register(new RegisterRequestDTO(address, OLD_PASSWORD, OLD_PASSWORD, "Synthetic", "User"));
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, address);
    }
    private UUID adminWithPeer(UUID admin) {
        UUID peer = registered();
        owner().update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", peer);
        return admin;
    }
    private MemoryRecoveryCapture issued(UUID user) { var capture = new MemoryRecoveryCapture(); capture.issue(service, user); return capture; }
    private static UUID selector(MemoryRecoveryCapture capture) { return PasswordRecoveryToken.parse(capture.token()).id(); }
    private static PasswordRecoveryReplacement replacement(String password) { return new PasswordRecoveryReplacement(password, password); }
    private String email(UUID user) { return jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, user); }
    private String password(UUID user) { return jdbc.queryForObject("SELECT password FROM users WHERE id=?", String.class, user); }
    private long version(UUID user) { return jdbc.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, user); }
    private Map<String,Object> row(UUID challenge) { return jdbc.queryForMap("SELECT consumed_at,revoked_at FROM password_recovery_challenges WHERE id=?", challenge); }
    private Instant deadline(UUID id) { return jdbc.queryForObject("SELECT expires_at FROM password_recovery_challenges WHERE id=?", java.sql.Timestamp.class, id).toInstant(); }
    private void assertRejected(MemoryRecoveryCapture capture) { assertThatThrownBy(() -> service.consume(capture.token(), replacement(NEW_PASSWORD))).isInstanceOf(PasswordRecoveryRejectedException.class).hasMessage("Password recovery rejected"); }
    private boolean attemptConsume(PasswordRecoveryService foundation, MemoryRecoveryCapture capture) { try { foundation.consume(capture.token(), replacement(NEW_PASSWORD)); return true; } catch (PasswordRecoveryRejectedException exception) { return false; } }
    private void asUser(UUID user, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email(user), null, java.util.List.of()));
        try { action.run(); } finally { SecurityContextHolder.clearContext(); }
    }
    private MemoryRecoveryCapture historical(UUID user, Instant expiry) {
        var token = PasswordRecoveryToken.generate();
        insertHistorical(token, user, version(user), expiry);
        var capture = new MemoryRecoveryCapture();
        org.springframework.test.util.ReflectionTestUtils.setField(capture, "token", token.encoded());
        return capture;
    }
    private MemoryRecoveryCapture historicalAfterRevocation(UUID user, Instant expiry) {
        jdbc.update("UPDATE password_recovery_challenges SET revoked_at=clock_timestamp() WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL", user);
        return historical(user, expiry);
    }
    private void insertHistorical(PasswordRecoveryToken token, UUID user, long version, Instant expiry) {
        owner().update("INSERT INTO password_recovery_challenges(id,user_id,format_version,verifier,issued_credential_version,created_at,expires_at) VALUES (?,?,1,?,?,?,?)",
                token.id(), user, token.verifier(user, version), version, java.sql.Timestamp.from(expiry.minusSeconds(900)), java.sql.Timestamp.from(expiry));
    }
    private static JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), MIGRATION, DB_PASSWORD)); }
    private static Connection connection(String identity) throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), identity, DB_PASSWORD); }
    private static void execute(Connection connection, String sql) throws SQLException { try (Statement statement = connection.createStatement()) { statement.execute(sql); } }
    private static int pid(Connection connection) throws SQLException { try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) { result.next(); return result.getInt(1); } }
    private static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Transaction synchronization timed out"); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); } }
    private static void awaitAdvisoryWait(AtomicInteger pid, int space) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted AND objsubid=?)", Boolean.class, pid.get(), space))) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Independent transaction did not wait on expected advisory domain");
    }
    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql.getSQLState();
        return "no_sql_state";
    }
    private static void assertSqlState(Runnable action, String state) { assertThatThrownBy(action::run).satisfies(error -> assertThat(sqlState(error)).isEqualTo(state)); }

    private final class ObservedTransactions implements PlatformTransactionManager {
        private final AtomicInteger pid = new AtomicInteger();
        private final CountDownLatch commitReady = new CountDownLatch(1);
        private final CountDownLatch commitRelease = new CountDownLatch(1);
        private boolean pauseCommit;
        private boolean rejectCommit;
        private boolean lockTimeout;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionStatus status = transactions.getTransaction(definition);
            pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            if (lockTimeout) jdbc.execute("SET LOCAL lock_timeout='100ms'");
            return status;
        }
        @Override public void commit(TransactionStatus status) {
            if (pauseCommit) { commitReady.countDown(); await(commitRelease); }
            if (rejectCommit) { transactions.rollback(status); throw new IllegalStateException("Synthetic commit rollback"); }
            transactions.commit(status);
        }
        @Override public void rollback(TransactionStatus status) { transactions.rollback(status); }
    }
}
