package com.company.shop.module.user.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.company.shop.module.user.dto.LoginRequestDTO;
import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.dto.PasswordRecoveryReplacement;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.AuthService;

import jakarta.validation.Validator;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LocalAdministratorRecoveryIT {
    private static final String DATABASE = "local_admin_recovery_test";
    private static final String MIGRATION = "admin_recovery_migration";
    private static final String RUNTIME = "admin_recovery_runtime";
    private static final String BOOTSTRAP = "admin_recovery_bootstrap";
    private static final String OPERATOR = "admin_recovery_reset";
    private static final String DB_PASSWORD = "synthetic_database_password";
    private static final String OLD_PASSWORD = "StrongPassword123!";
    private static final String NEW_PASSWORD = "ChangedPassword456!";
    private static final PostgreSQLContainer POSTGRES = container();

    static {
        POSTGRES.start();
        try (Connection admin = connection("postgres")) {
            provision(admin);
            execute(admin, "ALTER DATABASE " + DATABASE + " OWNER TO " + MIGRATION);
            execute(admin, "ALTER SCHEMA public OWNER TO " + MIGRATION);
            execute(admin, "GRANT CONNECT ON DATABASE " + DATABASE + " TO " + RUNTIME);
            execute(admin, "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            execute(admin, "ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION + " IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + RUNTIME);
            execute(admin, "ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION + " IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO " + RUNTIME);
        } catch (SQLException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @Autowired private AuthService auth;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder encoder;
    @Autowired private Validator validator;
    @Autowired private JdbcTemplate runtime;
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;

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
        try (Connection admin = connection("postgres")) {
            execute(admin, "ALTER ROLE " + OPERATOR + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            recovery(admin).configure(OPERATOR, RUNTIME, MIGRATION, BOOTSTRAP);
        }
    }

    @Test
    void enrollment_shouldRequireCurrentAccountControlAndProtectVerifier(CapturedOutput output) throws Exception {
        UUID admin = registered(true);
        try (Connection owner = connection("postgres")) {
            assertThatThrownBy(() -> recovery(owner).enroll(admin, "WrongPassword123!".toCharArray()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Current password rejected");
            assertThat(secretCount(admin)).isZero();
            for (char[] invalid : new char[][]{null, new char[0], " ".toCharArray(), "界".repeat(25).toCharArray()}) {
                assertThatThrownBy(() -> recovery(owner).enroll(admin, invalid)).isInstanceOf(IllegalArgumentException.class);
            }
            try (var secret = recovery(owner).enroll(admin, OLD_PASSWORD.toCharArray())) {
                byte[] verifier = owner().queryForObject("SELECT verifier FROM administrator_recovery.emergency_secrets WHERE id=?", byte[].class, secret.id());
                assertThat(Arrays.equals(verifier, secret.verifier(admin, DATABASE))).isTrue();
                assertThat(Arrays.equals(verifier, secret.bytes())).isFalse();
                assertThat(!output.getAll().contains(new String(secret.encoded()))).isTrue();
                assertThatThrownBy(() -> recovery(owner).enroll(admin, OLD_PASSWORD.toCharArray())).isInstanceOf(SQLException.class);
                assertThat(secretCount(admin)).isOne();
                for (String login : new String[]{RUNTIME, OPERATOR, BOOTSTRAP}) {
                    try (Connection actor = connection(login)) {
                        rejected(() -> execute(actor, "SELECT * FROM administrator_recovery.emergency_secrets"), "42501");
                        rejected(() -> recovery(actor).enroll(admin, OLD_PASSWORD.toCharArray()), "42501");
                        rejected(() -> recovery(actor).authorize(admin, version(admin), secret), "42501");
                        rejected(() -> execute(actor, "UPDATE administrator_recovery.emergency_secrets SET verifier=decode(repeat('00',32),'hex')"), "42501");
                    }
                }
                try (Connection migration = connection(MIGRATION)) {
                    rejected(() -> recovery(migration).authorize(admin, version(admin), secret), "42501");
                }
            }
        }
    }

    @Test
    void reset_shouldAtomicallyPreserveIdentityConsumeProofAndInvalidateJwt(CapturedOutput output) throws Exception {
        UUID admin = registered(true);
        String email = email(admin);
        String oldJwt = auth.login(new LoginRequestDTO(email, OLD_PASSWORD)).getToken();
        try (var secret = enrolled(admin)) {
            UUID authorization = authorized(admin, secret);
            assertThat(owner().queryForObject("SELECT expires_at-created_at=INTERVAL '10 minutes' FROM administrator_recovery.authorizations WHERE id=?", Boolean.class, authorization)).isTrue();
            UUID receipt;
            try (Connection actor = connection(OPERATOR)) {
                receipt = recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD));
                assertThat(recovery(actor).evidence(authorization)).isEqualTo(receipt);
                assertThat(recovery(actor).evidence(UUID.randomUUID())).isNull();
            }
            assertThat(version(admin)).isOne();
            assertThat(encoder.matches(NEW_PASSWORD, password(admin))).isTrue();
            assertThat(encoder.matches(OLD_PASSWORD, password(admin))).isFalse();
            assertThat(runtime.queryForMap("SELECT email,enabled,deleted FROM users WHERE id=?", admin))
                    .containsEntry("email", email).containsEntry("enabled", true).containsEntry("deleted", false);
            assertThat(runtime.queryForList("SELECT r.name FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?", String.class, admin))
                    .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
            assertThat(consumed("emergency_secrets", secret.id())).isTrue();
            assertThat(consumed("authorizations", authorization)).isTrue();
            assertThat(owner().queryForMap("SELECT operator_login::text,operation,credential_version_before,credential_version_after FROM administrator_recovery.reset_evidence WHERE id=?", receipt))
                    .containsEntry("operator_login", OPERATOR).containsEntry("operation", "RESET_SOLE_ADMIN")
                    .containsEntry("credential_version_before", 0L).containsEntry("credential_version_after", 1L);
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldJwt)).andExpect(status().isForbidden());
            String fresh = auth.login(new LoginRequestDTO(email, NEW_PASSWORD)).getToken();
            mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
            assertThatThrownBy(() -> auth.login(new LoginRequestDTO(email, OLD_PASSWORD))).isInstanceOf(com.company.shop.module.user.exception.InvalidCredentialsException.class);
            String evidence = owner().queryForObject("SELECT row_to_json(e)::text FROM administrator_recovery.reset_evidence e WHERE id=?", String.class, receipt);
            assertThat(evidence != null && !evidence.contains(new String(secret.encoded())) && !evidence.contains(password(admin)) && !evidence.contains(NEW_PASSWORD)).isTrue();
            assertThat(!output.getAll().contains(new String(secret.encoded())) && !output.getAll().contains(NEW_PASSWORD)).isTrue();
            String serverLogs = POSTGRES.getLogs();
            assertThat(!serverLogs.contains(java.util.HexFormat.of().formatHex(secret.bytes()))
                    && !serverLogs.contains(password(admin)) && !serverLogs.contains(NEW_PASSWORD)).isTrue();
            assertThat(runtime.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
            assertThat(runtime.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isZero();
            try (Connection actor = connection(OPERATOR)) { rejected(() -> nativeReset(actor, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "22023"); }
        }
    }

    @Test
    void proof_shouldRejectWrongUnknownRevokedConsumedAndVerifierAsBearer() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); var wrong = EmergencyRecoverySecret.generate(); Connection owner = connection("postgres")) {
            rejected(() -> recovery(owner).authorize(admin, 0, wrong), "42501");
            UUID authorization = authorized(admin, secret);
            try (Connection actor = connection(OPERATOR)) {
                rejected(() -> nativeReset(actor, authorization, admin, 0, wrong, encoder.encode(NEW_PASSWORD)), "22023");
                try (PreparedStatement sql = actor.prepareStatement("SELECT administrator_recovery.reset(?,?,?,?,?,?,?,'local')")) {
                    bindReset(sql, authorization, admin, 0, secret.id(), secret.verifier(admin, DATABASE), encoder.encode(NEW_PASSWORD), DATABASE);
                    rejected(sql::execute, "42501");
                }
                assertThat(consumed("emergency_secrets", secret.id())).isFalse();
                assertThat(consumed("authorizations", authorization)).isFalse();
            }
            recovery(owner).revokeSecret(secret.id(), admin);
            rejected(() -> recovery(owner).authorize(admin, 0, secret), "42501");
            assertThat(owner().queryForObject("SELECT revoked_at IS NOT NULL FROM administrator_recovery.authorizations WHERE id=?", Boolean.class, authorization)).isTrue();
            try (Connection actor = connection(OPERATOR)) { rejected(() -> nativeReset(actor, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "22023"); }
            assertThat(version(admin)).isZero();
            try (var next = recovery(owner).enroll(admin, OLD_PASSWORD.toCharArray())) {
                UUID nextAuthorization = authorized(admin, next);
                try (Connection actor = connection(OPERATOR)) { nativeReset(actor, nextAuthorization, admin, 0, next, encoder.encode(NEW_PASSWORD)); }
                rejected(() -> recovery(owner).authorize(admin, 1, next), "42501");
            }
        }
    }

    @Test
    void privileges_shouldRejectForgedAuthorityRoleSpoofingAndBroadRegrants() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR); Connection owner = connection("postgres")) {
            assertThatThrownBy(() -> DriverManager.getConnection(POSTGRES.getJdbcUrl(), OPERATOR, "wrong_synthetic_database_password"))
                    .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("28P01"));
            UUID authorization = authorized(admin, secret);
            for (String statement : new String[]{"UPDATE public.users SET password='forbidden'", "INSERT INTO public.user_roles SELECT id,id FROM public.users",
                    "INSERT INTO administrator_recovery.authorizations DEFAULT VALUES", "UPDATE administrator_recovery.operator_identity SET active=true",
                    "SET ROLE " + MIGRATION, "SELECT administrator_recovery.revoke_operator()", "SELECT administrator_recovery.invalidate_restored_authority()"}) {
                rejected(() -> execute(actor, statement), "42501");
            }
            for (String login : new String[]{RUNTIME, MIGRATION, BOOTSTRAP, "postgres"}) {
                try (Connection denied = connection(login)) { rejected(() -> nativeReset(denied, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "42501"); }
            }
            execute(owner, "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + RUNTIME);
            execute(owner, "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RUNTIME);
            try (Connection denied = connection(RUNTIME)) { rejected(() -> nativeReset(denied, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "42501"); }
            execute(owner, "GRANT " + RUNTIME + " TO " + OPERATOR);
            try { rejected(() -> nativeReset(actor, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "42501"); }
            finally { execute(owner, "REVOKE " + RUNTIME + " FROM " + OPERATOR); }
            assertThat(version(admin)).isZero();
            execute(owner, "SET SESSION AUTHORIZATION " + OPERATOR);
            try { rejected(() -> nativeReset(owner, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "42501"); }
            finally { execute(owner, "RESET SESSION AUTHORIZATION"); }
            var trustAttempt = POSTGRES.execInContainer("psql", "-X", "-h", "127.0.0.1", "-U", OPERATOR, "-d", DATABASE,
                    "-c", "SELECT administrator_recovery.reset(NULL,NULL,0,NULL,NULL,NULL,'" + DATABASE + "','local')");
            assertThat(trustAttempt.getExitCode()).isNotZero();
            assertThat(trustAttempt.getStderr()).contains("Reset operator rejected");
        }
    }

    @Test
    void targets_shouldRejectWrongUnknownNullNonAdminAndMultipleAdministrators() throws Exception {
        UUID admin = registered(true);
        UUID user = registered(false);
        try (var secret = enrolled(admin); Connection owner = connection("postgres")) {
            UUID authorization = authorized(admin, secret);
            for (UUID target : new UUID[]{user, UUID.randomUUID(), null}) {
                rejected(() -> recovery(owner).enroll(target, OLD_PASSWORD.toCharArray()), "22023");
                try (Connection actor = connection(OPERATOR)) { rejected(() -> nativeReset(actor, authorization, target, 0, secret, encoder.encode(NEW_PASSWORD)), "22023"); }
            }
            UUID peer = registered(true);
            rejected(() -> recovery(owner).authorize(admin, 0, secret), "22023");
            try (Connection actor = connection(OPERATOR)) { rejected(() -> nativeReset(actor, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "22023"); }
            userService.disable(admin);
            rejected(() -> recovery(owner).enroll(admin, OLD_PASSWORD.toCharArray()), "22023");
            userService.delete(admin);
            rejected(() -> recovery(owner).enroll(admin, OLD_PASSWORD.toCharArray()), "22023");
            assertThat(runtime.queryForObject("SELECT enabled AND NOT deleted FROM users WHERE id=?", Boolean.class, peer)).isTrue();
            assertThat(consumed("emergency_secrets", secret.id())).isFalse();
        }
    }

    @Test
    void rollbackAndEvidenceFailure_shouldLeaveEveryAuthorityAndCredentialUntouched() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            actor.setAutoCommit(false);
            nativeReset(actor, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD));
            assertThat(version(admin)).isZero();
            assertThat(consumed("emergency_secrets", secret.id())).isFalse();
            assertThat(evidenceCount(admin)).isZero();
            actor.rollback(); actor.setAutoCommit(true);
            owner().execute("CREATE FUNCTION administrator_recovery.reject_test_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rejection' USING ERRCODE='23514'; END $$");
            owner().execute("CREATE TRIGGER reject_test_receipt BEFORE INSERT ON administrator_recovery.reset_evidence FOR EACH ROW EXECUTE FUNCTION administrator_recovery.reject_test_receipt()");
            try { rejected(() -> recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)), "23514"); }
            finally { owner().execute("DROP TRIGGER reject_test_receipt ON administrator_recovery.reset_evidence"); owner().execute("DROP FUNCTION administrator_recovery.reject_test_receipt()"); }
            assertThat(version(admin)).isZero();
            assertThat(consumed("emergency_secrets", secret.id())).isFalse();
            assertThat(consumed("authorizations", authorization)).isFalse();
            assertThat(evidenceCount(admin)).isZero();
            recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD));
            assertThat(evidenceCount(admin)).isOne();
        }
    }

    @Test
    void passwordRules_shouldRejectBeforeMutationAndAcceptBcryptUtf8Boundary() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            for (var replacement : new PasswordRecoveryReplacement[]{null, replacement(""), replacement("short"), replacement(" ".repeat(10)),
                    replacement("a".repeat(73)), replacement("界".repeat(25)), new PasswordRecoveryReplacement(NEW_PASSWORD, "DifferentPassword123!")}) {
                assertThatThrownBy(() -> recovery(actor).reset(authorization, admin, 0, secret, replacement)).isInstanceOf(IllegalArgumentException.class);
            }
            rejected(() -> nativeReset(actor, authorization, admin, 0, secret, "invalid_hash"), "22023");
            rejected(() -> nativeReset(actor, authorization, admin, 0, secret, "$2a$10$" + "A".repeat(53)), "22023");
            String boundary = "界".repeat(24);
            recovery(actor).reset(authorization, admin, 0, secret, replacement(boundary));
            assertThat(encoder.matches(boundary, password(admin))).isTrue();
            assertThat(auth.login(new LoginRequestDTO(email(admin), boundary)).getToken() != null).isTrue();
        }
    }

    @Test
    void supersessionExpiryVersionAndContext_shouldRejectWithoutConsumingSecret() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection owner = connection("postgres"); Connection actor = connection(OPERATOR)) {
            UUID previous = authorized(admin, secret);
            UUID current = authorized(admin, secret);
            rejected(() -> nativeReset(actor, previous, admin, 0, secret, encoder.encode(NEW_PASSWORD)), "22023");
            rejected(() -> nativeReset(actor, current, admin, 1, secret, encoder.encode(NEW_PASSWORD)), "22023");
            rejected(() -> new LocalAdministratorRecovery(owner, "other_database", validator, encoder, 1000, 10000).authorize(admin, 0, secret), "42501");
            try (PreparedStatement sql = actor.prepareStatement("SELECT administrator_recovery.reset(?,?,?,?,?,?,?,'prod')")) {
                bindReset(sql, current, admin, 0, secret.id(), secret.bytes(), encoder.encode(NEW_PASSWORD), DATABASE);
                rejected(sql::execute, "42501");
            }
            recovery(owner).revokeSecret(secret.id(), admin);
            try (var next = recovery(owner).enroll(admin, OLD_PASSWORD.toCharArray())) {
                UUID expired = insertAuthorization(next.id(), admin, Instant.now().minusSeconds(660));
                rejected(() -> nativeReset(actor, expired, admin, 0, next, encoder.encode(NEW_PASSWORD)), "22023");
                assertThat(consumed("emergency_secrets", next.id())).isFalse();
            }
            assertThat(version(admin)).isZero();
        }
    }

    @Test
    void evidenceAndAuthorityHistory_shouldRejectMutationAndReopening() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD));
            for (String table : new String[]{"emergency_secrets", "authorizations", "reset_evidence"}) {
                for (String login : new String[]{OPERATOR, MIGRATION, "postgres"}) {
                    try (Connection denied = connection(login)) {
                        rejected(() -> execute(denied, "DELETE FROM administrator_recovery." + table), "42501");
                        rejected(() -> execute(denied, "TRUNCATE administrator_recovery." + table + " CASCADE"), "42501");
                    }
                }
            }
            for (String table : new String[]{"emergency_secrets", "authorizations"}) {
                rejected(() -> owner().update("UPDATE administrator_recovery." + table + " SET consumed_at=NULL WHERE target_user_id=?", admin), "23514");
                rejected(() -> owner().update("UPDATE administrator_recovery." + table + " SET created_at=created_at-INTERVAL '1 second' WHERE target_user_id=?", admin), "23514");
            }
            rejected(() -> owner().execute("UPDATE administrator_recovery.reset_evidence SET operation='RESET_SOLE_ADMIN'"), "42501");
            assertThat(evidenceCount(admin)).isOne();
        }
    }

    @Test
    void overflowAndHigherIsolation_shouldRollbackAuthorityAndVersion() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR)) {
            owner().update("UPDATE users SET credential_version=? WHERE id=?", Long.MAX_VALUE, admin);
            UUID authorization = authorized(admin, secret);
            rejected(() -> nativeReset(actor, authorization, admin, Long.MAX_VALUE, secret, encoder.encode(NEW_PASSWORD)), "22003");
            assertThat(version(admin)).isEqualTo(Long.MAX_VALUE);
            assertThat(consumed("emergency_secrets", secret.id())).isFalse();
            actor.setAutoCommit(false); actor.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            rejected(() -> nativeReset(actor, authorization, admin, Long.MAX_VALUE, secret, encoder.encode(NEW_PASSWORD)), "25001");
            actor.rollback();
        }
    }

    @Test
    void timeoutAndOrdering_shouldWaitInAdministratorDomainBeforeUserDomain() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection blocker = connection("postgres"); Connection actor = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            blocker.setAutoCommit(false); execute(blocker, "SELECT pg_advisory_xact_lock(1094995278,1)");
            rejected(() -> new LocalAdministratorRecovery(actor, DATABASE, validator, encoder, 100, 1000)
                    .reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)), "55P03");
            blocker.rollback();
            execute(blocker, "SELECT pg_advisory_xact_lock(hashtextextended('" + admin + "',0))");
            AtomicInteger waiting = new AtomicInteger();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var reset = executor.submit(() -> {
                    try (Connection connection = connection(OPERATOR)) {
                        waiting.set(pid(connection));
                        return recovery(connection).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD));
                    }
                });
                awaitLock(waiting, 1);
                assertThat(owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND granted AND objsubid=2 AND classid=1094995278 AND objid=1)", Boolean.class, waiting.get())).isTrue();
                blocker.rollback();
                assertThat(reset.get(10, TimeUnit.SECONDS)).isNotNull();
            }
        }
    }

    @Test
    void simultaneousResets_shouldWaitAndCommitExactlyOne() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection winner = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            winner.setAutoCommit(false);
            UUID receipt = nativeReset(winner, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD));
            AtomicInteger waiting = new AtomicInteger();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var loser = executor.submit(() -> {
                    try (Connection connection = connection(OPERATOR)) {
                        waiting.set(pid(connection));
                        try { recovery(connection).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)); return false; }
                        catch (SQLException rejected) { return true; }
                    }
                });
                awaitLock(waiting, 2); winner.commit();
                assertThat(loser.get(10, TimeUnit.SECONDS)).isTrue();
                winner.setAutoCommit(true);
                assertThat(recovery(winner).evidence(authorization)).isEqualTo(receipt);
            }
            assertThat(version(admin)).isOne(); assertThat(evidenceCount(admin)).isOne();
        }
    }

    @Test
    void passwordChangeWinning_shouldRejectWaitingResetAsStale() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection winner = connection(RUNTIME)) {
            UUID authorization = authorized(admin, secret);
            winner.setAutoCommit(false);
            execute(winner, "SELECT pg_advisory_xact_lock(1094995278,1)");
            execute(winner, "SELECT pg_advisory_xact_lock(hashtextextended('" + admin + "',0))");
            try (PreparedStatement sql = winner.prepareStatement("UPDATE users SET password=?,credential_version=credential_version+1 WHERE id=?")) {
                sql.setString(1, encoder.encode(NEW_PASSWORD)); sql.setObject(2, admin); sql.execute();
            }
            AtomicInteger waiting = new AtomicInteger();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var reset = executor.submit(() -> {
                    try (Connection connection = connection(OPERATOR)) {
                        waiting.set(pid(connection));
                        try { recovery(connection).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)); return false; }
                        catch (SQLException failure) { return failure.getSQLState().equals("22023"); }
                    }
                });
                awaitLock(waiting, 2); winner.commit();
                assertThat(reset.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(consumed("emergency_secrets", secret.id())).isFalse(); assertThat(evidenceCount(admin)).isZero();
        }
    }

    @Test
    void resetWinning_shouldMakeAuthenticatedPasswordChangeFailAndPreserveFinalAdmin() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection winner = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            winner.setAutoCommit(false); nativeReset(winner, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD));
            try (var executor = Executors.newSingleThreadExecutor()) {
                var change = executor.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email(admin), null, java.util.List.of()));
                    try {
                        userService.changeCurrentUserPassword(new PasswordChangeRequestDTO(OLD_PASSWORD, "AnotherPassword789!", "AnotherPassword789!"));
                        return false;
                    } catch (com.company.shop.module.user.exception.CurrentPasswordIncorrectException rejected) { return true; }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try { awaitRuntimeWait(); } finally { winner.commit(); }
                assertThat(change.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThatThrownBy(() -> userService.disable(admin)).isInstanceOf(com.company.shop.module.user.exception.UserAdministratorContinuityException.class);
            assertThatThrownBy(() -> userService.delete(admin)).isInstanceOf(com.company.shop.module.user.exception.UserAdministratorContinuityException.class);
            assertThat(version(admin)).isOne();
        }
    }

    @Test
    void inactiveLifecycleWinning_shouldMakeWaitingResetRejectEligibility() throws Exception {
        for (boolean retired : new boolean[]{false, true}) {
            owner().execute("TRUNCATE public.users CASCADE");
            UUID admin = registered(true);
            try (var secret = enrolled(admin); Connection winner = connection(MIGRATION)) {
                UUID authorization = authorized(admin, secret);
                UUID peer = registered(false);
                winner.setAutoCommit(false); execute(winner, "SELECT pg_advisory_xact_lock(1094995278,1)");
                execute(winner, "SELECT pg_advisory_xact_lock(hashtextextended('" + admin + "',0))");
                execute(winner, "INSERT INTO user_roles SELECT '" + peer + "',id FROM roles WHERE name='ROLE_ADMIN'");
                execute(winner, "UPDATE users SET " + (retired ? "deleted=true" : "enabled=false,credential_version=credential_version+1") + " WHERE id='" + admin + "'");
                AtomicInteger waiting = new AtomicInteger();
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var reset = executor.submit(() -> {
                        try (Connection connection = connection(OPERATOR)) {
                            waiting.set(pid(connection));
                            try { recovery(connection).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)); return false; }
                            catch (SQLException failure) { return failure.getSQLState().equals("22023"); }
                        }
                    });
                    awaitLock(waiting, 2); winner.commit();
                    assertThat(reset.get(10, TimeUnit.SECONDS)).isTrue();
                }
                assertThat(consumed("emergency_secrets", secret.id())).isFalse(); assertThat(evidenceCount(admin)).isZero();
            }
        }
    }

    @Test
    void resetWinning_shouldSerializeConcurrentFinalAdministratorRemovalAndRejectIt() throws Exception {
        for (boolean retire : new boolean[]{false, true}) {
            owner().execute("TRUNCATE public.users CASCADE");
            UUID admin = registered(true);
            try (var secret = enrolled(admin); Connection winner = connection(OPERATOR)) {
                UUID authorization = authorized(admin, secret);
                winner.setAutoCommit(false);
                nativeReset(winner, authorization, admin, 0, secret, encoder.encode(NEW_PASSWORD));
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var removal = executor.submit(() -> {
                        try {
                            if (retire) userService.delete(admin); else userService.disable(admin);
                            return false;
                        } catch (com.company.shop.module.user.exception.UserAdministratorContinuityException rejected) { return true; }
                    });
                    try { awaitRuntimeWait(); } finally { winner.commit(); }
                    assertThat(removal.get(10, TimeUnit.SECONDS)).isTrue();
                }
                assertThat(version(admin)).isOne();
                assertThat(runtime.queryForObject("SELECT enabled AND NOT deleted FROM users WHERE id=?", Boolean.class, admin)).isTrue();
            }
        }
    }

    @Test
    void v51AndV52_shouldRemainExcludedAndRevokeHistoricalOpenChallenge() throws Exception {
        UUID admin = registered(false);
        // A legacy pre-promotion challenge must remain unusable and be revoked by the atomic reset.
        runtime.update("WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now) INSERT INTO password_recovery_challenges(id,user_id,format_version,verifier,issued_credential_version,created_at,expires_at) SELECT ?,?,1,?,0,now,now+INTERVAL '15 minutes' FROM t", UUID.randomUUID(), admin, new byte[32]);
        owner().update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", admin);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR); Connection owner = connection("postgres")) {
            UUID authorization = authorized(admin, secret);
            recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD));
            assertThat(runtime.queryForObject("SELECT count(*) FROM password_recovery_challenges WHERE user_id=? AND revoked_at IS NOT NULL", Long.class, admin)).isOne();
            assertThatThrownBy(() -> runtime.update("WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now) INSERT INTO password_recovery_challenges(id,user_id,format_version,verifier,issued_credential_version,created_at,expires_at) SELECT ?,?,1,?,1,now,now+INTERVAL '15 minutes' FROM t", UUID.randomUUID(), admin, new byte[32])).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            rejected(() -> execute(actor, "SELECT administrator_lifecycle.promote_zero_administrator('" + admin + "','" + DATABASE + "','local')"), "42501");
            execute(owner, "INSERT INTO administrator_lifecycle.operator_identity(operator_login,database_name,environment_name) VALUES ('" + BOOTSTRAP + "','" + DATABASE + "','local') ON CONFLICT(singleton) DO NOTHING");
            execute(owner, "GRANT USAGE ON SCHEMA administrator_lifecycle TO " + BOOTSTRAP);
            execute(owner, "GRANT EXECUTE ON FUNCTION administrator_lifecycle.promote_zero_administrator(uuid,text,text) TO " + BOOTSTRAP);
            try (Connection bootstrap = connection(BOOTSTRAP)) { rejected(() -> execute(bootstrap, "SELECT administrator_lifecycle.promote_zero_administrator('" + admin + "','" + DATABASE + "','local')"), "55000"); }
        }
    }

    @Test
    void revocation_shouldTerminateExistingSessionsAndRemoveAllExecutionAuthority() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection actor = connection(OPERATOR); Connection owner = connection("postgres")) {
            UUID authorization = authorized(admin, secret);
            recovery(owner).revokeOperator(false);
            assertThatThrownBy(() -> execute(actor, "SELECT 1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection(OPERATOR)).isInstanceOf(SQLException.class);
            assertThat(owner().queryForObject("SELECT active FROM administrator_recovery.operator_identity", Boolean.class)).isFalse();
            assertThat(owner().queryForObject("SELECT revoked_at IS NOT NULL FROM administrator_recovery.authorizations WHERE id=?", Boolean.class, authorization)).isTrue();
            assertThat(owner().queryForObject("SELECT has_function_privilege(?, 'administrator_recovery.reset(uuid,uuid,bigint,uuid,bytea,text,text,text)', 'EXECUTE')", Boolean.class, OPERATOR)).isFalse();
            assertThat(consumed("emergency_secrets", secret.id())).isFalse();
        }
    }

    @Test
    void application_shouldExposeNoRecoveryBeanEndpointOrAutomaticOperator() {
        assertThat(context.getBeansOfType(LocalAdministratorRecovery.class)).isEmpty();
        assertThat(context.getBeansOfType(LocalAdministratorRecoveryCommand.class)).isEmpty();
        var mappings = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        assertThat(mappings.getHandlerMethods().values().stream().noneMatch(method -> method.getBeanType().getPackageName().contains(".operator"))).isTrue();
    }

    @Test
    void packagedEntrypoint_shouldRequireTerminalWithoutStartingApplication() throws Exception {
        Process process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.main=com.company.shop.module.user.operator.LocalAdministratorRecoveryCommand", "-cp",
                "target/enterprise-shop-0.0.1-SNAPSHOT.jar", "org.springframework.boot.loader.launch.PropertiesLauncher", "status")
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isOne();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(output).contains("A protected local terminal is required").doesNotContain("Started ShopApplication");
    }

    @Test
    void ambiguousCommit_shouldReconcileReceiptWithoutRepeatingMutation() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection real = connection(OPERATOR)) {
            UUID authorization = authorized(admin, secret);
            Connection uncertain = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(real, args);
                    if (method.getName().equals("commit")) throw new SQLException("Synthetic disconnect after commit", "08006");
                    return result;
                } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            rejected(() -> recovery(uncertain).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)), "08006");
            assertThat(recovery(real).evidence(authorization)).isNotNull();
            assertThat(version(admin)).isOne(); assertThat(consumed("emergency_secrets", secret.id())).isTrue();
            rejected(() -> recovery(real).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)), "22023");
            assertThat(evidenceCount(admin)).isOne();
        }
    }

    @Test
    void deadlockVictim_shouldLeaveEitherCompleteResetOrUnconsumedAuthority() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection blocker = connection("postgres")) {
            UUID authorization = authorized(admin, secret);
            blocker.setAutoCommit(false);
            execute(blocker, "SELECT pg_advisory_xact_lock(hashtextextended('" + admin + "',0))");
            AtomicInteger waiting = new AtomicInteger();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var reset = executor.submit(() -> {
                    try (Connection actor = connection(OPERATOR)) {
                        waiting.set(pid(actor));
                        try { recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)); return "committed"; }
                        catch (SQLException failure) { return failure.getSQLState(); }
                    }
                });
                awaitLock(waiting, 1);
                String blockerOutcome;
                try { execute(blocker, "SELECT pg_advisory_xact_lock(1094995278,1)"); blockerOutcome = "acquired"; }
                catch (SQLException failure) { blockerOutcome = failure.getSQLState(); }
                finally { blocker.rollback(); }
                String resetOutcome = reset.get(10, TimeUnit.SECONDS);
                assertThat(blockerOutcome.equals("40P01") || resetOutcome.equals("40P01")).isTrue();
                boolean committed = resetOutcome.equals("committed");
                assertThat(version(admin)).isEqualTo(committed ? 1 : 0);
                assertThat(consumed("emergency_secrets", secret.id())).isEqualTo(committed);
                assertThat(consumed("authorizations", authorization)).isEqualTo(committed);
                assertThat(evidenceCount(admin)).isEqualTo(committed ? 1 : 0);
            }
        }
    }

    @Test
    void authorizationExpiringWhileWaiting_shouldRejectAtDatabaseTimeWithoutConsumption() throws Exception {
        UUID admin = registered(true);
        try (var secret = enrolled(admin); Connection blocker = connection("postgres")) {
            UUID authorization = insertAuthorization(secret.id(), admin, Instant.now().minusSeconds(598));
            blocker.setAutoCommit(false); execute(blocker, "SELECT pg_advisory_xact_lock(1094995278,1)");
            AtomicInteger waiting = new AtomicInteger();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var reset = executor.submit(() -> {
                    try (Connection actor = connection(OPERATOR)) {
                        waiting.set(pid(actor));
                        try { recovery(actor).reset(authorization, admin, 0, secret, replacement(NEW_PASSWORD)); return false; }
                        catch (SQLException failure) { return failure.getSQLState().equals("22023"); }
                    }
                });
                awaitLock(waiting, 2);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(owner().queryForObject("SELECT clock_timestamp() >= expires_at FROM administrator_recovery.authorizations WHERE id=?", Boolean.class, authorization))) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Authorization expiry not observed");
                }
                blocker.rollback(); assertThat(reset.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(version(admin)).isZero(); assertThat(consumed("emergency_secrets", secret.id())).isFalse();
        }
    }

    @Test
    void restore_shouldInvalidateSameNameAuthorityPreserveHistoricalReceiptsAndRequireFreshEnrollment() throws Exception {
        UUID admin = registered(true);
        java.nio.file.Path dump = java.nio.file.Files.createTempFile("synthetic-admin-recovery-", ".dump");
        try (var first = enrolled(admin); Connection actor = connection(OPERATOR); Connection owner = connection("postgres")) {
            UUID firstAuthorization = authorized(admin, first);
            recovery(actor).reset(firstAuthorization, admin, 0, first, replacement(NEW_PASSWORD));
            String preRestoreJwt = auth.login(new LoginRequestDTO(email(admin), NEW_PASSWORD)).getToken();
            try (var second = recovery(owner).enroll(admin, NEW_PASSWORD.toCharArray())) {
                UUID pending = authorized(admin, second);
                assertThat(POSTGRES.execInContainer("pg_dump", "-U", "postgres", "-d", DATABASE, "-Fc", "-f", "/tmp/admin-recovery.dump").getExitCode()).isZero();
                POSTGRES.copyFileFromContainer("/tmp/admin-recovery.dump", dump.toString());
                recovery(actor).reset(pending, admin, 1, second, replacement("LaterPassword789!"));
                try (PostgreSQLContainer restored = container()) {
                    restored.start();
                    try (Connection administrative = DriverManager.getConnection(restored.getJdbcUrl(), "postgres", DB_PASSWORD)) { provision(administrative); }
                    restored.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/admin-recovery.dump");
                    assertThat(restored.execInContainer("pg_restore", "-U", "postgres", "-d", DATABASE, "--clean", "--if-exists", "--exit-on-error", "/tmp/admin-recovery.dump").getExitCode()).isZero();
                    Flyway.configure().dataSource(restored.getJdbcUrl(), MIGRATION, DB_PASSWORD).load().validate();
                    JdbcTemplate restoredOwner = new JdbcTemplate(new DriverManagerDataSource(restored.getJdbcUrl(), MIGRATION, DB_PASSWORD));
                    assertThat(restoredOwner.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, admin)).isOne();
                    assertThat(restoredOwner.queryForObject("SELECT consumed_at IS NULL AND revoked_at IS NULL FROM administrator_recovery.emergency_secrets WHERE id=?", Boolean.class, second.id())).isTrue();
                    assertThat(restoredOwner.queryForObject("SELECT count(*) FROM administrator_recovery.reset_evidence WHERE target_user_id=?", Long.class, admin)).isOne();
                    assertThat(restoredOwner.queryForObject("SELECT database_name=current_database() FROM administrator_recovery.operator_identity", Boolean.class)).isTrue();
                    var originalJwt = context.getBean(com.company.shop.security.jwt.JwtTokenProvider.class);
                    assertThat(originalJwt.validate(preRestoreJwt)).isTrue();
                    assertThat(originalJwt.getCredentialVersion(preRestoreJwt)).isEqualTo(1L);
                    byte[] newKey = new byte[32]; new java.security.SecureRandom().nextBytes(newKey);
                    var keys = new com.company.shop.security.jwt.JwtProperties(java.util.Base64.getEncoder().encodeToString(newKey),
                            "restored-fresh-key", null, null, 3600000);
                    var rotatedJwt = new com.company.shop.security.jwt.JwtTokenProvider(keys);
                    assertThat(rotatedJwt.validate(preRestoreJwt)).isFalse();
                    try (Connection administrative = DriverManager.getConnection(restored.getJdbcUrl(), "postgres", DB_PASSWORD)) {
                        LocalAdministratorRecovery tool = recovery(administrative);
                        tool.revokeOperator(true);
                        assertThat(restoredOwner.queryForObject("SELECT revoked_at IS NOT NULL FROM administrator_recovery.emergency_secrets WHERE id=?", Boolean.class, second.id())).isTrue();
                        assertThat(restoredOwner.queryForObject("SELECT revoked_at IS NOT NULL FROM administrator_recovery.authorizations WHERE id=?", Boolean.class, pending)).isTrue();
                        execute(administrative, "ALTER ROLE " + OPERATOR + " LOGIN");
                        tool.configure(OPERATOR, RUNTIME, MIGRATION, BOOTSTRAP);
                        rejected(() -> tool.authorize(admin, 1, second), "42501");
                        try (var fresh = tool.enroll(admin, NEW_PASSWORD.toCharArray())) {
                            assertThat(fresh.id()).isNotEqualTo(second.id());
                            assertThat(tool.authorize(admin, 1, fresh)).isNotNull();
                        }
                    }
                    assertThat(restoredOwner.queryForObject("SELECT count(*) FROM administrator_recovery.reset_evidence WHERE target_user_id=?", Long.class, admin)).isOne();
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(dump);
            POSTGRES.execInContainer("rm", "-f", "/tmp/admin-recovery.dump");
        }
    }

    private static PostgreSQLContainer container() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName(DATABASE).withUsername("postgres").withPassword(DB_PASSWORD)
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "scram-sha-256")
                .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.dict"), "/usr/local/share/postgresql/tsearch_data/polish.dict")
                .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.affix"), "/usr/local/share/postgresql/tsearch_data/polish.affix")
                .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.stop"), "/usr/local/share/postgresql/tsearch_data/polish.stop");
    }

    private static void provision(Connection admin) throws SQLException {
        for (String login : new String[]{MIGRATION, RUNTIME, BOOTSTRAP, OPERATOR}) {
            execute(admin, "CREATE ROLE " + login + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + DB_PASSWORD + "'");
        }
    }
    private LocalAdministratorRecovery recovery(Connection connection) { return new LocalAdministratorRecovery(connection, DATABASE, validator, encoder, 3000, 10000); }
    private UUID registered(boolean administrator) {
        String email = "local-recovery-" + UUID.randomUUID() + "@example.invalid";
        auth.register(new RegisterRequestDTO(email, OLD_PASSWORD, OLD_PASSWORD, "Local", "Synthetic"));
        UUID user = runtime.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, email);
        if (administrator) owner().update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", user);
        return user;
    }
    private EmergencyRecoverySecret enrolled(UUID target) throws Exception { try (Connection owner = connection("postgres")) { return recovery(owner).enroll(target, OLD_PASSWORD.toCharArray()); } }
    private UUID authorized(UUID target, EmergencyRecoverySecret secret) throws Exception { try (Connection owner = connection("postgres")) { return recovery(owner).authorize(target, version(target), secret); } }
    private long version(UUID target) { return runtime.queryForObject("SELECT credential_version FROM users WHERE id=?", Long.class, target); }
    private String password(UUID target) { return runtime.queryForObject("SELECT password FROM users WHERE id=?", String.class, target); }
    private String email(UUID target) { return runtime.queryForObject("SELECT email FROM users WHERE id=?", String.class, target); }
    private long secretCount(UUID target) { return owner().queryForObject("SELECT count(*) FROM administrator_recovery.emergency_secrets WHERE target_user_id=?", Long.class, target); }
    private long evidenceCount(UUID target) { return owner().queryForObject("SELECT count(*) FROM administrator_recovery.reset_evidence WHERE target_user_id=?", Long.class, target); }
    private boolean consumed(String table, UUID id) { return Boolean.TRUE.equals(owner().queryForObject("SELECT consumed_at IS NOT NULL FROM administrator_recovery." + table + " WHERE id=?", Boolean.class, id)); }
    private static PasswordRecoveryReplacement replacement(String password) { return new PasswordRecoveryReplacement(password, password); }
    private static JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), MIGRATION, DB_PASSWORD)); }
    private static Connection connection(String login) throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), login, DB_PASSWORD); }
    private static void execute(Connection connection, String sql) throws SQLException { try (Statement statement = connection.createStatement()) { statement.execute(sql); } }
    private static int pid(Connection connection) throws SQLException { try (Statement sql = connection.createStatement(); ResultSet result = sql.executeQuery("SELECT pg_backend_pid()")) { result.next(); return result.getInt(1); } }
    private static void bindReset(PreparedStatement sql, UUID authorization, UUID target, long version, UUID secret, byte[] proof, String hash, String database) throws SQLException {
        sql.setObject(1, authorization); sql.setObject(2, target); sql.setLong(3, version); sql.setObject(4, secret);
        sql.setBytes(5, proof); sql.setString(6, hash); sql.setString(7, database);
    }
    private static UUID nativeReset(Connection connection, UUID authorization, UUID target, long version, EmergencyRecoverySecret secret, String hash) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("SELECT administrator_recovery.reset(?,?,?,?,?,?,?,'local')")) {
            bindReset(sql, authorization, target, version, secret.id(), secret.bytes(), hash, DATABASE);
            try (ResultSet result = sql.executeQuery()) { result.next(); return result.getObject(1, UUID.class); }
        }
    }
    private UUID insertAuthorization(UUID secret, UUID target, Instant created) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO administrator_recovery.authorizations(id,secret_id,target_user_id,database_name,environment_name,operator_login,authorized_by,expected_credential_version,created_at,expires_at) VALUES (?,?,?,?,'local',?,? ,0,?,?)",
                id, secret, target, DATABASE, OPERATOR, "postgres", java.sql.Timestamp.from(created), java.sql.Timestamp.from(created.plusSeconds(600)));
        return id;
    }
    private static void rejected(SqlOperation operation, String state) {
        assertThatThrownBy(operation::run).satisfies(error -> {
            Throwable cause = error;
            while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
        });
    }
    @FunctionalInterface private interface SqlOperation { void run() throws Exception; }
    private static void awaitLock(AtomicInteger backend, int domain) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (backend.get() != 0 && Boolean.TRUE.equals(owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted AND objsubid=?)", Boolean.class, backend.get(), domain))) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected advisory wait not observed");
    }
    private static void awaitRuntimeWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.usename=? AND l.locktype='advisory' AND NOT l.granted AND l.objsubid=2)", Boolean.class, RUNTIME))) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Authenticated password change did not wait");
    }
}
