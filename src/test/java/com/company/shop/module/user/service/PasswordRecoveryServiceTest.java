package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.company.shop.module.user.dto.PasswordRecoveryReplacement;
import com.company.shop.module.user.entity.PasswordRecoveryChallenge;
import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.PasswordRecoveryRejectedException;
import com.company.shop.module.user.repository.PasswordRecoveryChallengeRepository;
import com.company.shop.module.user.repository.UserRepository;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class PasswordRecoveryServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordRecoveryChallengeRepository challenges = mock(PasswordRecoveryChallengeRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private PasswordRecoveryService service;

    @BeforeEach
    void prepare() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            service = new PasswordRecoveryService(users, challenges, encoder, factory.getValidator(), transactions);
        }
    }

    @Test
    void issue_shouldRejectMissingDisabledAndAdministratorTargetsWithoutCapture() {
        UUID id = UUID.randomUUID();
        var capture = new MemoryRecoveryCapture();
        assertThatThrownBy(() -> capture.issue(service, null)).isInstanceOf(PasswordRecoveryRejectedException.class);
        when(users.findActiveForUpdateById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> capture.issue(service, id)).isInstanceOf(PasswordRecoveryRejectedException.class);
        User disabled = new User("synthetic@example.invalid", "encoded", "Test", "User");
        disabled.disable();
        when(users.findActiveForUpdateById(id)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> capture.issue(service, id)).isInstanceOf(PasswordRecoveryRejectedException.class);
        User admin = new User("admin@example.invalid", "encoded", "Test", "User");
        admin.addRole(new Role("ROLE_ADMIN"));
        when(users.findActiveForUpdateById(id)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> capture.issue(service, id)).isInstanceOf(PasswordRecoveryRejectedException.class);
        assertThat(capture.token() == null).isTrue();
        assertThat(capture.deliveries()).isZero();
        verify(challenges, never()).revokeOpen(any());
    }

    @Test
    void consume_shouldValidateReplacementWithoutSecretBearingErrorsOrDatabaseWork() {
        String token = PasswordRecoveryToken.generate().encoded();
        var invalid = new PasswordRecoveryReplacement[]{null, new PasswordRecoveryReplacement(null, null),
                new PasswordRecoveryReplacement("short", "short"), new PasswordRecoveryReplacement("        ", "        "),
                new PasswordRecoveryReplacement("StrongPassword123!", "Mismatch123!"),
                new PasswordRecoveryReplacement("a".repeat(73), "a".repeat(73)),
                new PasswordRecoveryReplacement("🔐".repeat(19), "🔐".repeat(19))};
        for (var replacement : invalid) {
            assertThatThrownBy(() -> service.consume(token, replacement))
                    .isInstanceOf(PasswordRecoveryRejectedException.class).hasMessage("Password recovery rejected");
        }
        verify(users, never()).acquireAdministratorLifecycleLock();
    }

    @Test
    void consume_shouldRejectEveryInvalidStoredStateBeforeEncoding() {
        var token = PasswordRecoveryToken.generate();
        UUID id = UUID.randomUUID();
        User user = new User("test@example.invalid", "encoded", "Test", "User");
        when(challenges.findTargetId(token.id())).thenReturn(Optional.of(id));
        when(users.findActiveForUpdateById(id)).thenReturn(Optional.of(user));
        var stored = mock(PasswordRecoveryChallenge.class);
        when(challenges.findByIdForUpdate(token.id())).thenReturn(Optional.of(stored));
        when(stored.getFormatVersion()).thenReturn((short) 2);
        rejected(token);
        when(stored.getFormatVersion()).thenReturn((short) 1);
        when(stored.getUserId()).thenReturn(UUID.randomUUID());
        rejected(token);
        when(stored.getUserId()).thenReturn(id);
        when(stored.getIssuedCredentialVersion()).thenReturn(1L);
        rejected(token);
        when(stored.getIssuedCredentialVersion()).thenReturn(0L);
        when(challenges.currentDatabaseTime()).thenReturn(Instant.now());
        when(stored.isOpenAt(any())).thenReturn(false);
        rejected(token);
        when(stored.isOpenAt(any())).thenReturn(true);
        when(stored.getVerifier()).thenReturn(new byte[32]);
        rejected(token);
        verify(encoder, never()).encode(any());
    }

    @Test
    void consume_shouldRejectMissingRouteOrLockedRecordAndGuardFailure() {
        var token = PasswordRecoveryToken.generate();
        rejected(token);
        UUID id = UUID.randomUUID();
        when(challenges.findTargetId(token.id())).thenReturn(Optional.of(id));
        when(users.findActiveForUpdateById(id)).thenReturn(Optional.of(new User("test@example.invalid", "encoded", "T", "U")));
        rejected(token);
        var stored = mock(PasswordRecoveryChallenge.class);
        when(challenges.findByIdForUpdate(token.id())).thenReturn(Optional.of(stored));
        when(stored.getFormatVersion()).thenReturn((short) 1);
        when(stored.getUserId()).thenReturn(id);
        when(stored.isOpenAt(any())).thenReturn(true);
        when(stored.getVerifier()).thenReturn(token.verifier(id, 0));
        when(encoder.encode(any())).thenReturn("encoded");
        rejected(token);
        verify(users, never()).flush();
    }

    private void rejected(PasswordRecoveryToken token) {
        assertThatThrownBy(() -> service.consume(token.encoded(), new PasswordRecoveryReplacement("StrongPassword123!", "StrongPassword123!")))
                .isInstanceOf(PasswordRecoveryRejectedException.class);
    }
}
