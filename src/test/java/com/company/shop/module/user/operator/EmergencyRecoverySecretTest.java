package com.company.shop.module.user.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EmergencyRecoverySecretTest {
    @Test
    void proof_shouldRoundTripIndependentlyAndBindPurposeTargetAndDatabase() {
        UUID user = UUID.randomUUID();
        try (var first = EmergencyRecoverySecret.generate(); var second = EmergencyRecoverySecret.generate();
                var parsed = EmergencyRecoverySecret.parse(first.encoded())) {
            assertThat(parsed.id()).isEqualTo(first.id());
            assertThat(Arrays.equals(parsed.bytes(), first.bytes())).isTrue();
            assertThat(Arrays.equals(first.bytes(), second.bytes())).isFalse();
            assertThat(first.bytes().length).isEqualTo(32);
            assertThat(first.verifier(user, "local_db").length).isEqualTo(32);
            assertThat(Arrays.equals(first.verifier(user, "local_db"), parsed.verifier(user, "local_db"))).isTrue();
            assertThat(Arrays.equals(first.verifier(user, "local_db"), first.verifier(UUID.randomUUID(), "local_db"))).isFalse();
            assertThat(Arrays.equals(first.verifier(user, "local_db"), first.verifier(user, "other_db"))).isFalse();
            byte[] copy = first.bytes(); Arrays.fill(copy, (byte) 0);
            assertThat(Arrays.equals(copy, first.bytes())).isFalse();
        }
    }

    @Test
    void parse_shouldRejectMalformedUnsupportedAndNoncanonicalProofWithoutInputDiagnostics() {
        try (var secret = EmergencyRecoverySecret.generate()) {
            String value = new String(secret.encoded());
            for (String malformed : new String[]{"", "missing", "x".repeat(83), value.substring(0,82) + "B", value + "=", value.replace("v1.", "v2."),
                    "v1.AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA." + value.substring(40),
                    "v1." + "z".repeat(36) + "." + "A".repeat(43), value.substring(0, 40) + "!".repeat(43)}) {
                assertThatThrownBy(() -> EmergencyRecoverySecret.parse(malformed.toCharArray()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("Emergency proof rejected");
            }
            assertThatThrownBy(() -> EmergencyRecoverySecret.parse(null)).hasMessage("Emergency proof rejected");
        }
    }

    @Test
    void close_shouldClearOwnedSecretBytes() {
        var secret = EmergencyRecoverySecret.generate();
        secret.close();
        assertThat(Arrays.equals(secret.bytes(), new byte[32])).isTrue();
    }
}
