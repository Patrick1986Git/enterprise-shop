package com.company.shop.module.user.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.user.dto.PasswordRecoveryReplacement;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.PasswordRecoveryRejectedException;
import com.company.shop.module.user.repository.PasswordRecoveryChallengeRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.security.SecurityConstants;

import jakarta.validation.Validator;

/** Unwired foundation: only same-package test fixtures construct and invoke it. */
final class PasswordRecoveryService {
    private final UserRepository users;
    private final PasswordRecoveryChallengeRepository challenges;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final TransactionTemplate transaction;

    PasswordRecoveryService(UserRepository users, PasswordRecoveryChallengeRepository challenges,
            PasswordEncoder encoder, Validator validator, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.challenges = challenges;
        this.encoder = encoder;
        this.validator = validator;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    String issue(UUID userId) {
        if (userId == null) throw new PasswordRecoveryRejectedException();
        return transaction.execute(status -> {
            users.acquireAdministratorLifecycleLock();
            User user = lockEligibleUser(userId);
            PasswordRecoveryToken token = PasswordRecoveryToken.generate();
            challenges.revokeOpen(userId);
            challenges.insertChallenge(token.id(), userId, token.verifier(userId, user.getCredentialVersion()),
                    user.getCredentialVersion());
            return token.encoded();
        });
    }

    void consume(String encoded, PasswordRecoveryReplacement replacement) {
        PasswordRecoveryToken token = PasswordRecoveryToken.parse(encoded);
        if (replacement == null || !validator.validate(replacement).isEmpty()) {
            throw new PasswordRecoveryRejectedException();
        }
        transaction.executeWithoutResult(status -> {
            users.acquireAdministratorLifecycleLock();
            UUID userId = challenges.findTargetId(token.id()).orElseThrow(PasswordRecoveryRejectedException::new);
            User user = lockEligibleUser(userId);
            var challenge = challenges.findByIdForUpdate(token.id()).orElseThrow(PasswordRecoveryRejectedException::new);
            if (challenge.getFormatVersion() != 1 || !challenge.getUserId().equals(userId)
                    || challenge.getIssuedCredentialVersion() != user.getCredentialVersion()
                    || !challenge.isOpenAt(challenges.currentDatabaseTime())
                    || !token.matches(challenge.getVerifier(), userId, user.getCredentialVersion())) {
                throw new PasswordRecoveryRejectedException();
            }
            String encodedPassword = encoder.encode(replacement.getNewPassword());
            if (challenges.consumeOpen(token.id(), user.getCredentialVersion()) != 1) {
                throw new PasswordRecoveryRejectedException();
            }
            user.changePassword(encodedPassword);
            users.flush();
        });
    }

    private User lockEligibleUser(UUID userId) {
        users.acquireCommerceLifecycleLock(userId);
        User user = users.findActiveForUpdateById(userId).orElseThrow(PasswordRecoveryRejectedException::new);
        if (!user.isEnabled() || user.getRoles().stream().anyMatch(role -> SecurityConstants.ROLE_ADMIN.equals(role.getName()))) {
            throw new PasswordRecoveryRejectedException();
        }
        return user;
    }
}
