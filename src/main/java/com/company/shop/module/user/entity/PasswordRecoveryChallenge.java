package com.company.shop.module.user.entity;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "password_recovery_challenges")
public class PasswordRecoveryChallenge extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "format_version", nullable = false, updatable = false)
    private short formatVersion;
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] verifier;
    @Column(name = "issued_credential_version", nullable = false, updatable = false)
    private long issuedCredentialVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PasswordRecoveryChallenge() { }

    public UUID getUserId() { return userId; }
    public short getFormatVersion() { return formatVersion; }
    public byte[] getVerifier() { return verifier.clone(); }
    public long getIssuedCredentialVersion() { return issuedCredentialVersion; }

    public boolean isOpenAt(Instant observation) {
        return consumedAt == null && revokedAt == null && expiresAt.isAfter(observation);
    }
}
