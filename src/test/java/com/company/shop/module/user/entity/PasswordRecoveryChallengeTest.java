package com.company.shop.module.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordRecoveryChallengeTest {
    @Test
    void isOpenAt_shouldRejectAtAndAfterExpirationAndEveryTerminalState() {
        var challenge = new PasswordRecoveryChallenge();
        Instant expiry = Instant.parse("2026-10-12T01:00:00Z");
        ReflectionTestUtils.setField(challenge, "expiresAt", expiry);
        assertThat(challenge.isOpenAt(expiry.minusNanos(1))).isTrue();
        assertThat(challenge.isOpenAt(expiry)).isFalse();
        assertThat(challenge.isOpenAt(expiry.plusNanos(1))).isFalse();
        ReflectionTestUtils.setField(challenge, "consumedAt", expiry.minusSeconds(1));
        assertThat(challenge.isOpenAt(expiry.minusSeconds(2))).isFalse();
        ReflectionTestUtils.setField(challenge, "consumedAt", null);
        ReflectionTestUtils.setField(challenge, "revokedAt", expiry.minusSeconds(1));
        assertThat(challenge.isOpenAt(expiry.minusSeconds(2))).isFalse();
    }
}
