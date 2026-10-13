package com.company.shop.module.user.operator;

import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.company.shop.module.user.dto.PasswordRecoveryReplacement;

import jakarta.validation.Validation;

/** Local terminal entrypoint, launched explicitly from the packaged application. */
public final class LocalAdministratorRecoveryCommand {
    private LocalAdministratorRecoveryCommand() { }

    public static void main(String[] args) {
        Logger.getLogger("org.postgresql").setLevel(Level.OFF);
        int status = run(args, System.console(), DriverManager::getConnection);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, Console console, ConnectionFactory connections) {
        if (console == null) {
            System.err.println("A protected local terminal is required");
            return 1;
        }
        try {
            Options options = Options.parse(args);
            String confirmation = options.action().toUpperCase(java.util.Locale.ROOT) + " " + options.database()
                    + " " + (options.target() == null ? options.login() : options.target());
            if (!confirmation.equals(console.readLine("Type %s: ", confirmation))) throw new IllegalArgumentException();
            char[] databasePassword = console.readPassword("PostgreSQL login password: ");
            if (databasePassword == null || databasePassword.length == 0) throw new IllegalArgumentException();
            Properties properties = new Properties();
            properties.setProperty("user", options.login());
            properties.setProperty("password", new String(databasePassword));
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", Integer.toString((options.statementTimeout() + 999) / 1000 + 5));
            properties.setProperty("ApplicationName", "enterprise-shop-local-admin-recovery");
            properties.setProperty("preferQueryMode", "extended");
            Arrays.fill(databasePassword, '\0');
            try (Connection connection = connections.open(options.url(), properties);
                    var factory = Validation.buildDefaultValidatorFactory()) {
                properties.remove("password");
                LocalAdministratorRecovery recovery = new LocalAdministratorRecovery(connection, options.database(),
                        factory.getValidator(), new BCryptPasswordEncoder(), options.lockTimeout(), options.statementTimeout());
                execute(console, options, recovery);
            } finally { properties.remove("password"); }
            return 0;
        } catch (Exception exception) {
            console.printf("Operation was not confirmed successful. Reconcile receipts and current state before a confirmed retry.%n");
            return 1;
        }
    }

    @FunctionalInterface interface ConnectionFactory {
        Connection open(String url, Properties properties) throws java.sql.SQLException;
    }

    private static void execute(Console console, Options options, LocalAdministratorRecovery recovery) throws Exception {
        switch (options.action()) {
            case "configure" -> {
                recovery.configure(console.readLine("Reset-only login: "), console.readLine("Runtime login: "),
                        console.readLine("Flyway owner login: "), console.readLine("V51 bootstrap login: "));
                console.printf("Reset operator configured.%n");
            }
            case "enroll" -> {
                char[] password = console.readPassword("Current ADMIN application password: ");
                try (EmergencyRecoverySecret secret = recovery.enroll(options.target(), password)) {
                    char[] encoded = secret.encoded();
                    try {
                        console.printf("Enrollment committed. Record this emergency secret privately; it is shown once:%n");
                        console.writer().println(encoded);
                        console.writer().flush();
                    } finally { Arrays.fill(encoded, '\0'); }
                } finally { if (password != null) Arrays.fill(password, '\0'); }
            }
            case "authorize", "reset" -> {
                char[] input = console.readPassword("Enrolled emergency secret: ");
                try (EmergencyRecoverySecret secret = EmergencyRecoverySecret.parse(input)) {
                    if (options.action().equals("authorize")) {
                        UUID id = recovery.authorize(options.target(), options.version(), secret);
                        console.printf("Authorization committed for ten minutes. Non-secret operation ID: %s%n", id);
                    } else {
                        UUID authorization = UUID.fromString(console.readLine("Authorization operation ID: "));
                        char[] password = console.readPassword("Replacement application password: ");
                        char[] repeat = console.readPassword("Repeat replacement password: ");
                        try {
                            UUID receipt = recovery.reset(authorization, options.target(), options.version(), secret,
                                    new PasswordRecoveryReplacement(password == null ? null : new String(password),
                                            repeat == null ? null : new String(repeat)));
                            console.printf("Reset committed. Evidence ID: %s%nReconcile, log in, then revoke operator access and sessions.%n", receipt);
                        } finally {
                            if (password != null) Arrays.fill(password, '\0');
                            if (repeat != null) Arrays.fill(repeat, '\0');
                        }
                    }
                } finally { if (input != null) Arrays.fill(input, '\0'); }
            }
            case "revoke-secret" -> {
                recovery.revokeSecret(UUID.fromString(console.readLine("Non-secret enrollment ID: ")), options.target());
                console.printf("Emergency authority revoked; history retained.%n");
            }
            case "revoke-operator", "invalidate-restored" -> {
                recovery.revokeOperator(options.action().equals("invalidate-restored"));
                console.printf("Operator capability revoked and sessions terminated; history retained.%n");
                if (options.action().equals("invalidate-restored")) {
                    console.printf("Before traffic, replace JWT material and remove every pre-restore verification key; revoke open V52 challenges.%n");
                }
            }
            case "status" -> {
                UUID receipt = recovery.evidence(UUID.fromString(console.readLine("Authorization operation ID: ")));
                console.printf(receipt == null ? "No committed receipt at this recovery point. Inspect current state before retry.%n"
                        : "Committed evidence ID: %s%n", receipt);
            }
            default -> throw new IllegalArgumentException();
        }
    }

    record Options(String action, String host, int port, String database, String login, UUID target,
            long version, int lockTimeout, int statementTimeout) {
        static Options parse(String[] args) {
            if (args.length < 1 || !java.util.Set.of("configure", "enroll", "authorize", "reset", "revoke-secret",
                    "revoke-operator", "invalidate-restored", "status").contains(args[0])) throw new IllegalArgumentException();
            Map<String, String> values = new HashMap<>();
            java.util.Set<String> allowed = java.util.Set.of("--host", "--port", "--database", "--login", "--target",
                    "--version", "--lock-timeout-ms", "--statement-timeout-ms");
            for (int i = 1; i < args.length; i += 2) {
                if (i + 1 >= args.length || !allowed.contains(args[i]) || values.put(args[i], args[i + 1]) != null) {
                    throw new IllegalArgumentException();
                }
            }
            String host = values.get("--host");
            if (!java.util.Set.of("127.0.0.1", "::1").contains(host)) throw new IllegalArgumentException();
            String database = values.get("--database");
            String login = values.get("--login");
            if (database == null || login == null || !database.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                    || !login.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException();
            int port = Integer.parseInt(values.get("--port"));
            int lock = Integer.parseInt(values.get("--lock-timeout-ms"));
            int statement = Integer.parseInt(values.get("--statement-timeout-ms"));
            if (port < 1 || port > 65535 || lock < 1 || statement <= lock || statement > 2_147_000_000) throw new IllegalArgumentException();
            UUID target = values.containsKey("--target") ? UUID.fromString(values.get("--target")) : null;
            if (java.util.Set.of("enroll", "authorize", "reset", "revoke-secret").contains(args[0]) && target == null) {
                throw new IllegalArgumentException();
            }
            long version = values.containsKey("--version") ? Long.parseLong(values.get("--version")) : -1;
            if (java.util.Set.of("authorize", "reset").contains(args[0]) && version < 0) throw new IllegalArgumentException();
            return new Options(args[0], host, port, database, login, target, version, lock, statement);
        }

        String url() { return "jdbc:postgresql://" + (host.equals("::1") ? "[::1]" : host) + ":" + port + "/" + database; }
    }
}
