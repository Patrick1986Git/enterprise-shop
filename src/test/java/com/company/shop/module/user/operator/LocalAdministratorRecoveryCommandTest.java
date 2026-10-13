package com.company.shop.module.user.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Console;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class LocalAdministratorRecoveryCommandTest {
    private static final UUID TARGET = UUID.randomUUID();

    private String[] arguments(String action) {
        return new String[]{action, "--host", "127.0.0.1", "--port", "5433", "--database", "local_db",
                "--login", "local_owner", "--target", TARGET.toString(), "--version", "0",
                "--lock-timeout-ms", "1000", "--statement-timeout-ms", "5000"};
    }

    @Test
    void options_shouldRequireExplicitLocalBoundedNonsecretConnectionAndTarget() {
        var options = LocalAdministratorRecoveryCommand.Options.parse(arguments("reset"));
        assertThat(options.url()).isEqualTo("jdbc:postgresql://127.0.0.1:5433/local_db");
        String[] ipv6 = arguments("reset"); ipv6[2] = "::1";
        assertThat(LocalAdministratorRecoveryCommand.Options.parse(ipv6).url()).contains("[::1]");
        for (int index : new int[]{2, 4, 6, 8, 10, 12, 14, 16}) {
            String[] invalid = arguments("reset"); invalid[index] = "invalid;value";
            assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(RuntimeException.class);
        }
        for (String[] invalid : new String[][]{new String[]{}, new String[]{"unknown"}, new String[]{"reset", "--password", "synthetic"},
                new String[]{"reset", "--host"}, new String[]{"reset", "--host", "127.0.0.1", "--host", "127.0.0.1"}}) {
            assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        String[] invalid = arguments("reset"); invalid[4] = "0";
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        invalid[4] = "65536";
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        invalid[4] = "5433"; invalid[12] = "-1";
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        invalid[12] = "0"; invalid[16] = "1000";
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        String[] noTarget = new String[]{"reset", "--host", "127.0.0.1", "--port", "5433", "--database", "local_db",
                "--login", "local_owner", "--lock-timeout-ms", "1000", "--statement-timeout-ms", "5000"};
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(noTarget)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTerminalOrConfirmation_shouldNeverConnect() {
        AtomicBoolean connected = new AtomicBoolean();
        var connection = (LocalAdministratorRecoveryCommand.ConnectionFactory) (url, properties) -> {
            connected.set(true); throw new java.sql.SQLException();
        };
        assertThat(LocalAdministratorRecoveryCommand.run(arguments("reset"), null, connection)).isOne();
        Console console = mock(Console.class);
        when(console.readLine(anyString(), any())).thenReturn("wrong");
        assertThat(LocalAdministratorRecoveryCommand.run(arguments("reset"), console, connection)).isOne();
        assertThat(connected).isFalse();
    }

    @Test
    void command_shouldUseTerminalSecretsAndSuppressConnectionFailures() {
        Console console = mock(Console.class);
        when(console.readLine(anyString(), any())).thenAnswer(call -> call.getArgument(1));
        when(console.readPassword(anyString())).thenReturn("synthetic_database_password".toCharArray());
        assertThat(LocalAdministratorRecoveryCommand.run(arguments("reset"), console, (url, properties) -> {
            assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:5433/local_db");
            assertThat(properties.getProperty("preferQueryMode")).isEqualTo("extended");
            throw new java.sql.SQLException("synthetic_sensitive_diagnostic");
        })).isOne();
        org.mockito.Mockito.verify(console).printf("Operation was not confirmed successful. Reconcile receipts and current state before a confirmed retry.%n");
    }

    @Test
    void missingDatabaseCredential_shouldNeverConnectOrFallBackToTrust() {
        for (char[] missing : new char[][]{null, new char[0]}) {
            Console console = mock(Console.class);
            when(console.readLine(anyString(), any())).thenAnswer(call -> call.getArgument(1));
            when(console.readPassword(anyString())).thenReturn(missing);
            AtomicBoolean connected = new AtomicBoolean();
            assertThat(LocalAdministratorRecoveryCommand.run(arguments("reset"), console, (url, properties) -> {
                connected.set(true); throw new java.sql.SQLException();
            })).isOne();
            assertThat(connected).isFalse();
        }
    }

    @Test
    void eofEmergencyOrReplacementInput_shouldRejectWithoutResetMutation() throws Exception {
        for (String missing : new String[]{"emergency", "Replacement", "Repeat"}) {
            Console console = mock(Console.class);
            when(console.readLine(anyString(), any())).thenAnswer(call -> call.getArgument(1));
            when(console.readLine(anyString())).thenReturn(UUID.randomUUID().toString());
            try (var secret = EmergencyRecoverySecret.generate()) {
                when(console.readPassword(anyString())).thenAnswer(call -> {
                    String prompt = call.getArgument(0);
                    if (prompt.contains(missing)) return null;
                    return prompt.contains("emergency") ? secret.encoded() : "StrongPassword123!".toCharArray();
                });
                Connection connection = mock(Connection.class);
                assertThat(LocalAdministratorRecoveryCommand.run(arguments("reset"), console, (url, properties) -> connection)).isOne();
                org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).prepareStatement(anyString());
            }
        }
    }

    @Test
    void connectionOptions_shouldRejectImplicitLoginAndUnboundedWaitsAndAllowMetadataOnlyConfiguration() {
        java.util.List<String> missingLogin = new java.util.ArrayList<>(java.util.List.of(arguments("configure")));
        missingLogin.subList(7,9).clear();
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(missingLogin.toArray(String[]::new))).isInstanceOf(IllegalArgumentException.class);
        String[] invalid = arguments("reset"); invalid[14] = "0";
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        invalid[14] = "1000"; invalid[16] = "2147000001";
        assertThatThrownBy(() -> LocalAdministratorRecoveryCommand.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        java.util.List<String> configuration = new java.util.ArrayList<>(java.util.List.of(arguments("configure")));
        configuration.subList(9,13).clear();
        assertThat(LocalAdministratorRecoveryCommand.Options.parse(configuration.toArray(String[]::new)).target()).isNull();
    }

    @Test
    void commands_shouldRunEveryExplicitLifecycleActionWithoutArgumentSecrets() throws Exception {
        for (String action : new String[]{"configure", "enroll", "authorize", "reset", "revoke-secret", "revoke-operator", "invalidate-restored", "status"}) {
            Console console = mock(Console.class);
            StringWriter output = new StringWriter(); when(console.writer()).thenReturn(new PrintWriter(output));
            when(console.readLine(anyString(), any())).thenAnswer(call -> call.getArgument(1));
            when(console.readLine(anyString())).thenReturn(UUID.randomUUID().toString());
            try (var secret = EmergencyRecoverySecret.generate()) {
                when(console.readPassword(anyString())).thenAnswer(call -> call.<String>getArgument(0).contains("emergency")
                        ? secret.encoded() : "StrongPassword123!".toCharArray());
                Connection connection = mock(Connection.class);
                Statement statement = mock(Statement.class); ResultSet settings = mock(ResultSet.class);
                when(connection.getAutoCommit()).thenReturn(true);
                when(connection.createStatement()).thenReturn(statement);
                when(statement.executeQuery(anyString())).thenReturn(settings);
                when(settings.next()).thenReturn(true); when(settings.getBoolean(1)).thenReturn(true);
                PreparedStatement prepared = mock(PreparedStatement.class); ResultSet result = mock(ResultSet.class);
                when(connection.prepareStatement(anyString())).thenReturn(prepared);
                when(prepared.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true);
                when(result.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
                when(result.getString(1)).thenReturn(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("StrongPassword123!"));
                assertThat(LocalAdministratorRecoveryCommand.run(arguments(action), console, (url, properties) -> connection)).isZero();
                if (!action.equals("enroll")) assertThat(output.toString().isEmpty()).isTrue();
            }
        }
    }
}
