package com.company.shop.module.user.operator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Offline-only emergency proof; its selector is not authorization. */
public final class EmergencyRecoverySecret implements AutoCloseable {
    private final UUID id;
    private final byte[] secret;

    private EmergencyRecoverySecret(UUID id, byte[] secret) {
        this.id = id;
        this.secret = secret;
    }

    public static EmergencyRecoverySecret generate() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new EmergencyRecoverySecret(UUID.randomUUID(), secret);
    }

    public static EmergencyRecoverySecret parse(char[] input) {
        if (input == null || input.length != 83) throw rejected();
        String encoded = new String(input);
        String[] parts = encoded.split("\\.", -1);
        try {
            if (parts.length != 3 || !parts[0].equals("v1")) throw rejected();
            UUID id = UUID.fromString(parts[1]);
            byte[] secret = Base64.getUrlDecoder().decode(parts[2]);
            if (!parts[1].equals(id.toString()) || secret.length != 32
                    || !parts[2].equals(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))) {
                Arrays.fill(secret, (byte) 0);
                throw rejected();
            }
            return new EmergencyRecoverySecret(id, secret);
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    public UUID id() { return id; }

    byte[] bytes() { return secret.clone(); }

    public char[] encoded() {
        return ("v1." + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)).toCharArray();
    }

    byte[] verifier(UUID target, String database) {
        String purpose = "enterprise-shop/local-admin-emergency/v1\n" + id + "\n" + target + "\n" + database + "\nlocal\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            return digest.digest(secret);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Emergency verifier unavailable");
        }
    }

    @Override public void close() { Arrays.fill(secret, (byte) 0); }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("Emergency proof rejected");
    }
}
