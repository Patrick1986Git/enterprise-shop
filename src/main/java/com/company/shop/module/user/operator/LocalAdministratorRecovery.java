package com.company.shop.module.user.operator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.dto.PasswordRecoveryReplacement;

import jakarta.validation.Validator;

/** Explicit offline commands; never a Spring bean or application recovery caller. */
public final class LocalAdministratorRecovery {
    private final Connection connection;
    private final String database;
    private final Validator validator;
    private final PasswordEncoder encoder;
    private final int lockTimeout;
    private final int statementTimeout;

    public LocalAdministratorRecovery(Connection connection, String database, Validator validator,
            PasswordEncoder encoder, int lockTimeout, int statementTimeout) {
        if (lockTimeout <= 0 || statementTimeout <= lockTimeout) throw new IllegalArgumentException("Invalid timeouts");
        this.connection = connection;
        this.database = database;
        this.validator = validator;
        this.encoder = encoder;
        this.lockTimeout = lockTimeout;
        this.statementTimeout = statementTimeout;
    }

    public void configure(String operator, String runtime, String migration, String bootstrap) throws SQLException {
        transaction(true, () -> {
            try (PreparedStatement sql = connection.prepareStatement(
                    "SELECT administrator_recovery.configure_operator(?::name,?::name,?::name,?::name,?)")) {
                sql.setString(1, operator); sql.setString(2, runtime); sql.setString(3, migration);
                sql.setString(4, bootstrap); sql.setString(5, database); sql.execute();
            }
            return null;
        });
    }

    public EmergencyRecoverySecret enroll(UUID target, char[] currentPassword) throws SQLException {
        String password = currentPassword == null ? null : new String(currentPassword);
        if (!validator.validateProperty(new PasswordChangeRequestDTO(password, null, null), "currentPassword").isEmpty()) {
            throw new IllegalArgumentException("Current password rejected");
        }
        EmergencyRecoverySecret secret = EmergencyRecoverySecret.generate();
        try {
            transaction(true, () -> {
                long version;
                try (PreparedStatement sql = connection.prepareStatement(
                        "SELECT password_hash,credential_version FROM administrator_recovery.enrollment_target(?,?,'local')")) {
                    sql.setObject(1, target); sql.setString(2, database);
                    try (ResultSet result = sql.executeQuery()) {
                        if (!result.next() || !encoder.matches(password, result.getString(1))) {
                            throw new IllegalArgumentException("Current password rejected");
                        }
                        version = result.getLong(2);
                    }
                }
                byte[] verifier = secret.verifier(target, database);
                try (PreparedStatement sql = connection.prepareStatement(
                        "SELECT administrator_recovery.enroll(?,?,?, ?,?,'local')")) {
                    sql.setObject(1, secret.id()); sql.setObject(2, target); sql.setLong(3, version);
                    sql.setBytes(4, verifier); sql.setString(5, database); sql.execute();
                } finally { Arrays.fill(verifier, (byte) 0); }
                return null;
            });
            return secret;
        } catch (SQLException | RuntimeException exception) {
            secret.close();
            throw exception;
        }
    }

    public UUID authorize(UUID target, long version, EmergencyRecoverySecret secret) throws SQLException {
        return transaction(true, () -> {
            byte[] proof = secret.bytes();
            try (PreparedStatement sql = connection.prepareStatement(
                    "SELECT administrator_recovery.authorize(?,?,?,?,?,'local')")) {
                sql.setObject(1, target); sql.setLong(2, version); sql.setObject(3, secret.id());
                sql.setBytes(4, proof); sql.setString(5, database);
                return receipt(sql);
            } finally { Arrays.fill(proof, (byte) 0); }
        });
    }

    public UUID reset(UUID authorization, UUID target, long version, EmergencyRecoverySecret secret,
            PasswordRecoveryReplacement replacement) throws SQLException {
        if (replacement == null || !validator.validate(replacement).isEmpty()) {
            throw new IllegalArgumentException("Replacement password rejected");
        }
        String hash = encoder.encode(replacement.getNewPassword());
        return transaction(false, () -> {
            byte[] proof = secret.bytes();
            try (PreparedStatement sql = connection.prepareStatement(
                    "SELECT administrator_recovery.reset(?,?,?,?,?,?,?,'local')")) {
                sql.setObject(1, authorization); sql.setObject(2, target); sql.setLong(3, version);
                sql.setObject(4, secret.id()); sql.setBytes(5, proof); sql.setString(6, hash); sql.setString(7, database);
                return receipt(sql);
            } finally { Arrays.fill(proof, (byte) 0); }
        });
    }

    public void revokeSecret(UUID secret, UUID target) throws SQLException {
        transaction(true, () -> {
            try (PreparedStatement sql = connection.prepareStatement(
                    "SELECT administrator_recovery.revoke_secret(?, ?,?,'local')")) {
                sql.setObject(1, secret); sql.setObject(2, target); sql.setString(3, database); sql.execute();
            }
            return null;
        });
    }

    public void revokeOperator(boolean restored) throws SQLException {
        transaction(true, () -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute(restored ? "SELECT administrator_recovery.invalidate_restored_authority()"
                        : "SELECT administrator_recovery.revoke_operator()");
            }
            return null;
        });
        transaction(true, () -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute("SELECT administrator_recovery.terminate_operator_sessions()");
            }
            return null;
        });
    }

    public UUID evidence(UUID authorization) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT id FROM administrator_recovery.reset_evidence WHERE authorization_id=?")) {
            sql.setObject(1, authorization);
            try (ResultSet result = sql.executeQuery()) { return result.next() ? result.getObject(1, UUID.class) : null; }
        }
    }

    private static UUID receipt(PreparedStatement sql) throws SQLException {
        try (ResultSet result = sql.executeQuery()) {
            if (!result.next()) throw new SQLException("Recovery receipt unavailable");
            return result.getObject(1, UUID.class);
        }
    }

    private <T> T transaction(boolean owner, SqlOperation<T> operation) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("A fresh offline transaction is required");
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        boolean transactionEnded = false;
        try {
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL lock_timeout='" + lockTimeout + "ms'");
                sql.execute("SET LOCAL statement_timeout='" + statementTimeout + "ms'");
                if (owner) {
                    sql.execute("SET LOCAL log_statement='none'");
                    sql.execute("SET LOCAL log_min_duration_statement=-1");
                    sql.execute("SET LOCAL log_min_duration_sample=-1");
                    sql.execute("SET LOCAL log_transaction_sample_rate=0");
                    sql.execute("SET LOCAL log_parameter_max_length=0");
                    sql.execute("SET LOCAL log_parameter_max_length_on_error=0");
                    sql.execute("SET LOCAL log_error_verbosity='terse'");
                }
                // This check carries no secret and precedes every sensitive prepared statement.
                try (ResultSet result = sql.executeQuery("""
                        SELECT system_user IN ('scram-sha-256:' || session_user, 'md5:' || session_user)
                          AND current_setting('log_statement')='none'
                          AND current_setting('log_min_duration_statement')='-1'
                          AND current_setting('log_min_duration_sample')='-1'
                          AND current_setting('log_transaction_sample_rate')::numeric=0
                          AND current_setting('log_parameter_max_length')='0'
                          AND current_setting('log_parameter_max_length_on_error')='0'
                          AND current_setting('log_error_verbosity')='terse'
                          AND coalesce(current_setting('pgaudit.log',true),'') IN ('','none')
                          AND coalesce(current_setting('auto_explain.log_min_duration',true),'-1')='-1'
                        """)) {
                    if (!result.next() || !result.getBoolean(1)) throw new SQLException("Sensitive session rejected");
                }
            }
            T result = operation.run();
            connection.commit();
            transactionEnded = true;
            return result;
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); transactionEnded = true; } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
                try { connection.close(); } catch (SQLException closeFailure) { exception.addSuppressed(closeFailure); }
            }
            throw exception;
        } finally { if (transactionEnded && !connection.isClosed()) connection.setAutoCommit(true); }
    }

    @FunctionalInterface private interface SqlOperation<T> { T run() throws SQLException; }
}
