package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.company.shop.module.user.exception.PasswordRecoveryRejectedException;
import com.tngtech.archunit.core.importer.ClassFileImporter;

class PasswordRecoveryTokenTest {
    @Test
    void generate_shouldRoundTripCanonicalIndependentSecretWithoutRevealingIt() {
        var token = PasswordRecoveryToken.generate();
        String encoded = token.encoded();
        var parsed = PasswordRecoveryToken.parse(encoded);
        UUID user = UUID.randomUUID();
        assertThat(encoded.length()).isEqualTo(83);
        assertThat(Base64.getUrlDecoder().decode(encoded.split("\\.")[2]).length).isEqualTo(32);
        assertThat(parsed.id()).isEqualTo(token.id());
        assertThat(parsed.matches(token.verifier(user, 3), user, 3)).isTrue();
        assertThat(token.toString()).isEqualTo("PasswordRecoveryToken[redacted]");
        assertThat(token.verifier(user, 3).length).isEqualTo(32);
        assertThat(PasswordRecoveryToken.generate().matches(token.verifier(user, 3), user, 3)).isFalse();
    }

    @Test
    void verifier_shouldBindPurposeVersionSelectorUserAndCredentialVersion() throws Exception {
        var token = PasswordRecoveryToken.generate();
        UUID user = UUID.randomUUID();
        byte[] secret = Base64.getUrlDecoder().decode(token.encoded().split("\\.")[2]);
        byte[] purpose = "enterprise-shop/password-recovery".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer encoded = ByteBuffer.allocate(4 + purpose.length + 74);
        encoded.putInt(purpose.length).put(purpose).putShort((short) 1)
                .putLong(token.id().getMostSignificantBits()).putLong(token.id().getLeastSignificantBits())
                .putLong(user.getMostSignificantBits()).putLong(user.getLeastSignificantBits())
                .putLong(7).put(secret);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(encoded.array());
        assertThat(token.matches(expected, user, 7)).isTrue();
        assertThat(token.matches(expected, UUID.randomUUID(), 7)).isFalse();
        assertThat(token.matches(expected, user, 8)).isFalse();
        byte[] otherPurpose = encoded.array().clone();
        otherPurpose[4] ^= 1;
        assertThat(token.matches(MessageDigest.getInstance("SHA-256").digest(otherPurpose), user, 7)).isFalse();
        byte[] otherVersion = encoded.array().clone();
        otherVersion[4 + purpose.length + 1] = 2;
        assertThat(token.matches(MessageDigest.getInstance("SHA-256").digest(otherVersion), user, 7)).isFalse();
        assertThat(token.matches(new byte[0], user, 7)).isFalse();
    }

    @Test
    void parse_shouldRejectMalformedUnsupportedAndNoncanonicalCredentialsWithoutEcho() {
        var token = PasswordRecoveryToken.generate();
        String encoded = token.encoded();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = encoded.charAt(encoded.length() - 1);
        String noncanonical = encoded.substring(0, encoded.length() - 1)
                + alphabet.charAt(alphabet.indexOf(last) + 1);
        String[] invalid = {null, "", encoded + "=", encoded.substring(1), encoded.replace("v1.", "v2."),
                encoded.replace("v1.", "xx."), "v1." + "z".repeat(36) + encoded.substring(39),
                encoded.substring(0, 40) + "%".repeat(43), noncanonical,
                encoded.substring(0, 39) + "-" + encoded.substring(40)};
        for (String candidate : invalid) {
            assertThatThrownBy(() -> PasswordRecoveryToken.parse(candidate))
                    .isInstanceOf(PasswordRecoveryRejectedException.class)
                    .hasMessage("Password recovery rejected");
        }
        String uppercase = "v1." + token.id().toString().toUpperCase(java.util.Locale.ROOT) + encoded.substring(39);
        if (!uppercase.equals(encoded)) {
            assertThatThrownBy(() -> PasswordRecoveryToken.parse(uppercase)).isInstanceOf(PasswordRecoveryRejectedException.class);
        }
    }

    @Test
    void matches_shouldUseJdkConstantTimeDigestComparison() {
        var type = new ClassFileImporter().importClass(PasswordRecoveryToken.class);
        assertThat(type.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTargetOwner().isEquivalentTo(MessageDigest.class) && call.getName().equals("isEqual"))).isTrue();
    }
}
