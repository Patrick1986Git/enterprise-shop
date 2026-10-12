package com.company.shop.module.user.operator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.company.shop.module.user.dto.PasswordRecoveryReplacement;

import jakarta.validation.Validation;

class LocalAdministratorRecoveryTest {
    @Test
    void unsafeLoggingOrExistingTransaction_shouldRejectBeforeBindingSecret() throws Exception {
        Connection connection = mock(Connection.class);
        try (var validation = Validation.buildDefaultValidatorFactory(); var secret = EmergencyRecoverySecret.generate()) {
            var recovery = new LocalAdministratorRecovery(connection, "local_db", validation.getValidator(), new BCryptPasswordEncoder(), 100, 1000);
            when(connection.getAutoCommit()).thenReturn(false);
            assertThatThrownBy(() -> recovery.authorize(UUID.randomUUID(), 0, secret)).isInstanceOf(SQLException.class).hasMessage("A fresh offline transaction is required");
            when(connection.getAutoCommit()).thenReturn(true);
            Statement statement = mock(Statement.class); ResultSet settings = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(settings);
            when(settings.next()).thenReturn(true); when(settings.getBoolean(1)).thenReturn(false);
            assertThatThrownBy(() -> recovery.reset(UUID.randomUUID(), UUID.randomUUID(), 0, secret,
                    new PasswordRecoveryReplacement("Password123!", "Password123!"))).isInstanceOf(SQLException.class).hasMessage("Sensitive session rejected");
            verify(connection, never()).prepareStatement(anyString());
            verify(connection).rollback();
        }
    }

    @Test
    void rollbackFailure_shouldCloseConnectionWithoutImplicitAutocommit() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        Statement statement = mock(Statement.class); ResultSet settings = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(settings);
        when(settings.next()).thenReturn(true); when(settings.getBoolean(1)).thenReturn(true);
        PreparedStatement sql = mock(PreparedStatement.class); ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(sql); when(sql.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);
        org.mockito.Mockito.doThrow(new SQLException("Synthetic connection loss")).when(connection).rollback();
        org.mockito.Mockito.doAnswer(call -> { when(connection.isClosed()).thenReturn(true); return null; }).when(connection).close();
        try (var validation = Validation.buildDefaultValidatorFactory(); var secret = EmergencyRecoverySecret.generate()) {
            var recovery = new LocalAdministratorRecovery(connection, "local_db", validation.getValidator(), new BCryptPasswordEncoder(), 100, 1000);
            assertThatThrownBy(() -> recovery.authorize(UUID.randomUUID(), 0, secret)).isInstanceOf(SQLException.class).hasMessage("Recovery receipt unavailable");
            verify(connection).close();
            verify(connection, never()).setAutoCommit(true);
            verify(connection, never()).commit();
        }
    }

    @Test
    void nonpositiveOrInvertedTimeouts_shouldReject() {
        for (int[] timeouts : new int[][]{{0, 100}, {100, 100}, {100, 99}}) {
            assertThatThrownBy(() -> new LocalAdministratorRecovery(null, "local_db", null, null, timeouts[0], timeouts[1]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid timeouts");
        }
    }
}
