package com.company.shop.module.user.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import com.company.shop.module.user.exception.PasswordRecoveryRejectedException;

final class PasswordRecoveryToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] PURPOSE = "enterprise-shop/password-recovery".getBytes(StandardCharsets.US_ASCII);
    private static final short FORMAT_VERSION = 1;
    private final UUID id;
    private final byte[] secret;

    private PasswordRecoveryToken(UUID id, byte[] secret) {
        this.id = id;
        this.secret = secret;
    }

    static PasswordRecoveryToken generate() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new PasswordRecoveryToken(UUID.randomUUID(), secret);
    }

    static PasswordRecoveryToken parse(String encoded) {
        if (encoded == null || encoded.length() != 83) throw new PasswordRecoveryRejectedException();
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("v1")) throw new PasswordRecoveryRejectedException();
        try {
            UUID id = UUID.fromString(parts[1]);
            byte[] secret = Base64.getUrlDecoder().decode(parts[2]);
            if (!id.toString().equals(parts[1]) || secret.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(secret).equals(parts[2])) {
                throw new PasswordRecoveryRejectedException();
            }
            return new PasswordRecoveryToken(id, secret);
        } catch (IllegalArgumentException exception) {
            throw new PasswordRecoveryRejectedException();
        }
    }

    UUID id() { return id; }

    String encoded() {
        return "v1." + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    byte[] verifier(UUID userId, long credentialVersion) {
        ByteBuffer input = ByteBuffer.allocate(4 + PURPOSE.length + 2 + 16 + 16 + 8 + 32);
        input.putInt(PURPOSE.length).put(PURPOSE).putShort(FORMAT_VERSION)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits())
                .putLong(credentialVersion).put(secret);
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.array());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    boolean matches(byte[] expected, UUID userId, long credentialVersion) {
        return MessageDigest.isEqual(expected, verifier(userId, credentialVersion));
    }

    @Override
    public String toString() { return "PasswordRecoveryToken[redacted]"; }
}
